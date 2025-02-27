package com.tfyre.bambu.view.dashboard;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.model.AmsSingle;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.model.Print;
import com.tfyre.bambu.model.Tray;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuConst.Speed;
import com.tfyre.bambu.printer.BambuErrors;
import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.view.LogsView;
import com.tfyre.bambu.view.ShowInterface;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.theme.lumo.LumoUtility;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.jboss.logging.Logger;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public class DashboardPrinter implements ShowInterface {

    private static final Logger log = Logger.getLogger(DashboardPrinter.class.getName());
    //DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter DTF = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();

    private final BambuPrinter printer;
    private final ProgressBar progressBar;
    private final Span progressFile = newSpan();
    private final Span progressTime = newSpan();
    private final Span progressLayer = newSpan();
    private final TextArea statusBox;
    private final Image monitorLamp = new Image(Images.MONITOR_LAMP_OFF.getImage(), "Monitor Lamp");
    private final Span monitorLampText = newSpan();
    private final Image bedImage = new Image(Images.MONITOR_BED_TEMP.getImage(), "Bed");
    private final Span bed = newSpan();
    private final Span bedTarget = newSpan();
    private final Image nozzleImage = new Image(Images.MONITOR_NOZZLE_TEMP.getImage(), "Nozzle");
    private final Span nozzle = newSpan();
    private final Span nozzleTarget = newSpan();
    private final Image frameImage = new Image(Images.MONITOR_FRAME_TEMP.getImage(), "Frame");
    private final Span frame = newSpan();
    private final Image speedImage = new Image(Images.MONITOR_SPEED.getImage(), "Speed");
    private final Span speed = newSpan();
    private final Image thumbnail = new Image();
    private final Span thumbnailUpdated = newSpan();
    private final Div printerName = new Div();
    private String thumbnailId;
    private boolean built;
    private boolean processFull = true;
    private final boolean isAdmin;
    private int lastError = 0;

    private final Map<String, AmsHeader> amsHeaders = new HashMap<>();
    private final Map<String, AmsFilament> amsFilaments = new HashMap<>();
    private String printType = BambuConst.PRINT_TYPE_IDLE;

    public DashboardPrinter(final BambuPrinter printer) {
        this.printer = printer;
        progressBar = newProgressBar();
        statusBox = newStatusBox();
        isAdmin = SecurityUtils.userHasAccess(SystemRoles.ROLE_ADMIN);
    }

    private Span newSpan() {
        return new Span("---");
    }

    private ProgressBar newProgressBar() {
        final ProgressBar result = new ProgressBar(0.0, 100.0);
        result.addClassName("progress");
        result.setIndeterminate(true);
        return result;
    }

    private TextArea newStatusBox() {
        final TextArea result = new TextArea("status");
        result.setValue("unknown");
        result.setSizeFull();
        return result;
    }

    private void setTemperature(final Span span, final double value) {
        span.setText("%.2fºC".formatted(value));
    }

    private double parseDouble(final String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            log.errorf("%s: Cannot parseDouble [%s]", printer.getName(), value);
            return 0;
        }
    }

    private Images getHumidityImage(final String id) {
        if ("0".equals(id)) {
            return Images.AMS_HUMIDITY_0;
        }
        if ("1".equals(id)) {
            return Images.AMS_HUMIDITY_1;
        }
        if ("2".equals(id)) {
            return Images.AMS_HUMIDITY_2;
        }
        if ("3".equals(id)) {
            return Images.AMS_HUMIDITY_3;
        }
        if ("4".equals(id)) {
            return Images.AMS_HUMIDITY_4;
        }
        return Images.AMS_HUMIDITY_0;
    }

    private void processAms(final com.tfyre.bambu.model.Ams ams) {
        ams.getAmsList().forEach(single -> {
            Optional.ofNullable(amsHeaders.get(getAmsHeaderId(single.getId()))).ifPresent(header -> {
                setTemperature(header.temperature(), parseDouble(single.getTemp()));
                header.humidity().setSrc(getHumidityImage(single.getHumidity()).getImage());
            });

            single.getTrayList().forEach(tray -> {
                Optional.ofNullable(amsFilaments.get(getFilamentTrayId(single, tray))).ifPresent(filament -> {
                    if (!tray.hasTrayInfoIdx()) {
                        filament.type().setText("Empty");
                        return;
                    }
                    filament.type().setText(BambuConst.getFilament(tray.getTrayInfoIdx()).orElse("Unknown"));
                    filament.color().getStyle().setBackgroundColor("#%s".formatted(tray.getTrayColor()));
                });
            });
        });
    }

    private void processVtTray(final com.tfyre.bambu.model.VtTray tray) {
        Optional.ofNullable(amsHeaders.get(getTrayId(tray.getId()))).ifPresent(header -> {
            setTemperature(header.temperature(), parseDouble(tray.getTrayTemp()));
        });
        Optional.ofNullable(amsFilaments.get(getTrayId(tray.getId()))).ifPresent(filament -> {
            filament.type().setText(BambuConst.getFilament(tray.getTrayInfoIdx()).orElse("Unknown"));
            filament.color().getStyle().setBackgroundColor("#%s".formatted(tray.getTrayColor()));
        });
    }

    private String formatTime(final Duration duration) {
        final StringBuilder sb = new StringBuilder();
        final long days = duration.toDays();
        if (days > 0) {
            sb.append(days)
                    .append(" day(s) ");
        }
        sb
                .append(duration.toHoursPart())
                .append(" hour(s) ")
                .append(duration.toMinutesPart())
                .append(" minute(s)");
        return sb.toString();
    }

    private void processPrint(final BambuPrinter.Message message, final Print print) {
        //Percetage
        if (print.hasMcPercent()) {
            progressBar.setIndeterminate(false);
            progressBar.setValue(Math.min(print.getMcPercent(), 100));
        }

        //FileName
        if (print.hasGcodeFile()) {
            progressFile.setText(print.getGcodeFile());
        }

        //Time
        if (print.hasMcRemainingTime()) {
            progressTime.setText("%s remaining".formatted(formatTime(Duration.ofMinutes(print.getMcRemainingTime()))));
        }

        //Layers
        if (print.hasLayerNum()) {
            progressLayer.setText("Layer %d / %d".formatted(print.getLayerNum(), printer.getTotalLayerNum()));
        }

        //Bed & Target Temperature
        if (print.hasBedTemper()) {
            setTemperature(bed, print.getBedTemper());
            bedImage.setSrc(print.getBedTemper() > 0.0 ? Images.MONITOR_BED_TEMP_ACTIVE.getImage() : Images.MONITOR_BED_TEMP.getImage());
        }
        if (print.hasBedTargetTemper()) {
            setTemperature(bedTarget, print.getBedTargetTemper());
        }

        //Nozzle & Target Temperature
        if (print.hasNozzleTemper()) {
            setTemperature(nozzle, print.getNozzleTemper());
            nozzleImage.setSrc(print.getNozzleTemper() > 0.0 ? Images.MONITOR_NOZZLE_TEMP_ACTIVE.getImage() : Images.MONITOR_NOZZLE_TEMP.getImage());
        }
        if (print.hasNozzleTargetTemper()) {
            setTemperature(nozzleTarget, print.getNozzleTargetTemper());
        }

        //Frame/Chamber Temperature
        if (print.hasChamberTemper()) {
            setTemperature(frame, print.getChamberTemper());
        }

        //Speed
        if (print.hasSpdLvl()) {
            speed.setText(Speed.fromSpeed(print.getSpdLvl()).getDescription());
        }

        if (print.hasAms() && print.getAms().getAmsCount() > 0) {
            processAms(print.getAms());
        } else if (print.hasVtTray()) {
            processVtTray(print.getVtTray());
        }

        print.getLightsReportList().stream()
                .filter(lr -> BambuConst.CHAMBER_LIGHT.equals(lr.getNode()))
                .findFirst()
                .ifPresent(lr -> {
                    monitorLampText.setText(lr.getMode());
                    monitorLamp.setSrc(BambuConst.LightMode.ON.getValue().equals(lr.getMode()) ? Images.MONITOR_LAMP_ON.getImage() : Images.MONITOR_LAMP_OFF.getImage());
                });

        statusBox.setValue(
                """
                Command: %s
                Sequence: %s
                Nozzle: %.2fºC
                Bed: %.2fºC
                Updated: %s
                """
                        .formatted(
                                print.getCommand(),
                                print.getSequenceId(),
                                print.getNozzleTemper(),
                                1.0 * print.getBedTemper(),
                                DTF.format(message.lastUpdated())
                        ));
    }

    private <T> void process(final boolean hasValue, final BambuPrinter.Message message, final T data, final BiConsumer<BambuPrinter.Message, T> consumer) {
        if (!hasValue) {
            return;
        }
        consumer.accept(message, data);
    }

    private void processError(final BambuPrinter.Message message) {
        final int error = printer.getPrintError();
        final String errorString;
        final boolean hasError;
        if (error == 0) {
            hasError = false;
            errorString = "";
        } else {
            hasError = true;
            errorString = BambuErrors.getPrinterError(error)
                    .orElseGet(() -> "Unknown error %s".formatted(Integer.toHexString(error)));
        }

        if (error != lastError) {
            lastError = error;
            if (lastError != 0) {
                showError("%s error: %s".formatted(printer.getName(), errorString));
            }
        }

        final String extra = hasError ? " / Print Error %s".formatted(errorString) : "";
        printerName.setTitle("Last Updated: %s%s".formatted(DTF.format(message.lastUpdated()), extra));
        if (hasError) {
            printerName.addClassName(LumoUtility.Background.ERROR_50);
        } else {
            printerName.removeClassName(LumoUtility.Background.ERROR_50);
        }
    }

    private void processPrintType() {
        final String _printType = printer.getPrintType();
        if (printType.equals(_printType)) {
            return;
        }
        printType = _printType;
        if (BambuConst.PRINT_TYPE_IDLE.equals(printType)) {
            //FIXME: show green notice + print complete
            showNotification("%s: Printer Idle".formatted(printer.getName()));
        }
    }

    private void processMessage(final BambuPrinter.Message message) {
        process(message.message().hasPrint(), message, message.message().getPrint(), this::processPrint);

        processError(message);
        processPrintType();
    }

    public void update() {
        if (!built) {
            return;
        }
        if (processFull) {
            printer.getFullStatus().ifPresent(message -> {
                processFull = false;
                processMessage(message);
            });
        }
        printer.getStatus().ifPresent(this::processMessage);
        printer.getThumbnail().ifPresent(data -> {
            if (data.thumbnail().getId().equals(thumbnailId)) {
                return;
            }
            thumbnailId = data.thumbnail().getId();
            thumbnail.setSrc(data.thumbnail());
            thumbnailUpdated.setText(DTF.format(data.lastUpdated()));
        });
    }

    private Div buildName() {
        printerName.addClassName("name");
        printerName.setTitle("--");
        printerName.setText(printer.getName());
        return printerName;
    }

    private Component buildImage() {
        thumbnailUpdated.getStyle().setColor("#fff");

        final Div result = new Div();
        result.addClassName("image");
        result.add(thumbnail, thumbnailUpdated);
        return result;
    }

    private VerticalLayout getBadge() {
        final VerticalLayout result = new VerticalLayout();
        result.setMargin(true);
        result.setSpacing(false);
        result.setSizeUndefined();
        return result;
    }

    private VerticalLayout getBadge(final String toolTip, final Component... components) {
        final VerticalLayout result = getBadge();
        result.addClassNames(
                "badge",
                toolTip.toLowerCase()
        );
        result.getElement().setProperty("title", toolTip);
        result.add(components);
        return result;
    }

    private <T extends Component> T wrapMonitorMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = new ContextMenu(result);
        Arrays.asList(BambuConst.LightMode.values())
                .forEach(lm -> {
                    menu.addItem("Set %s".formatted(lm.getValue()), l -> printer.commandLight(lm));
                });
        return result;
    }

    private <T extends Component> T wrapSpeedMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = new ContextMenu(result);
        final SubMenu control = menu.addItem("Control").getSubMenu();
        Arrays.asList(BambuConst.CommandControl.values())
                .forEach(cc -> control.addItem(cc.getValue(), l ->
                        YesNoCancelDialog.show("%s: Are you sure?".formatted(cc.getValue()), ync -> {
                            if (!ync.isConfirmed()) {
                                return;
                            }
                            printer.commandControl(cc);
                        })
                ));
        final SubMenu _speed = menu.addItem("Speed").getSubMenu();
        Arrays.asList(BambuConst.Speed.values())
                .forEach(s -> {
                    if (s == BambuConst.Speed.UNKNOWN) {
                        return;
                    }
                    _speed.addItem(s.getDescription(), l ->
                            YesNoCancelDialog.show("%s: Are you sure?".formatted(s.getDescription()), ync -> {
                                if (!ync.isConfirmed()) {
                                    return;
                                }
                                printer.commandSpeed(s);
                            }
                            )
                    );
                });
        return result;
    }

    private <T extends Component> T wrapNameMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = new ContextMenu(result);
        menu.addItem("Show Log", l -> UI.getCurrent().navigate(LogsView.class, printer.getName()));
        menu.addItem("Request Full Status", l -> printer.commandFullStatus(true));
        menu.addItem("Clear Error", l -> printer.commandClearPrinterError());
        return result;
    }

    private FlexLayout buildStatus() {
        final FlexLayout result = new FlexLayout();
        result.addClassName("status");
        result.add(
                getBadge("Bed", bedImage, bed, bedTarget),
                getBadge("Nozzle", nozzleImage, nozzle, nozzleTarget),
                //FIXME implement frame temperature detection (using printer model)
                //getBadge("Frame", frameImage, frame),
                wrapSpeedMenu(getBadge("Speed", speedImage, speed)),
                wrapMonitorMenu(getBadge("Lamp", monitorLamp, monitorLampText))
        );
        return result;
    }

    private HorizontalLayout buildProgressBar() {
        final HorizontalLayout result = new HorizontalLayout(progressFile, progressTime, progressLayer);
        result.addClassName("progress");
        return result;
    }

    private Div buildAmsHeader(final AmsHeader header) {
        amsHeaders.put(header.id(), header);
        final Div result = new Div();
        result.addClassName("amsheader");
        final Span name = new Span(header.id());
        final Span filler = new Span();
        filler.addClassName("filler");
        result.add(name, filler, header.temperature(), header.humidity());
        return result;
    }

    private Div buildAmsTray() {
        final Div result = new Div();
        result.addClassName("amstray");
        return result;
    }

    private Div buildAmsFilament(final String amsTrayId) {
        final Span color = new Span();
        color.addClassName("color");
        final AmsFilament filament = new AmsFilament(amsTrayId, new Span(), color);
        amsFilaments.put(amsTrayId, filament);

        final Div result = new Div();
        result.addClassName("filament");
        result.add(filament.type(), filament.color());
        return result;
    }

    private String getFilamentTrayId(final AmsSingle single, final Tray tray) {
        return "single[%s]tray[%s]".formatted(single.getId(), tray.getId());
    }

    private String getAmsHeaderId(final String id) {
        return "AMS#%s".formatted(id);
    }

    private String getTrayId(final String id) {
        return "Tray#%s".formatted(id);
    }

    private Div buildTray(final String amsHeaderId, final boolean hasHumidity, final List<Div> filaments) {
        final Image image = new Image(Images.AMS_HUMIDITY_0.getImage(), "Humidity");
        image.setTitle("Humidity");
        final AmsHeader amsHeader = new AmsHeader(amsHeaderId,
                new Span("--"),
                image
        );
        if (!hasHumidity) {
            amsHeader.humidity().getStyle().setDisplay(Style.Display.NONE);
        }
        final Div trayL = buildAmsTray();
        filaments.forEach(trayL::add);
        final Div layout = new Div();
        layout.addClassName("ams");
        layout.add(buildAmsHeader(amsHeader), trayL);
        return layout;
    }

    private void buildAms(final Div parent, final com.tfyre.bambu.model.Ams ams) {
        ams.getAmsList().forEach(single -> {
            parent.add(buildTray(
                    getAmsHeaderId(single.getId()),
                    true,
                    single.getTrayList().stream()
                            .map(tray -> buildAmsFilament(getFilamentTrayId(single, tray)))
                            .toList()
            ));
        });
    }

    private void buildVtTray(final Div parent, final com.tfyre.bambu.model.VtTray tray) {
        parent.add(buildTray(
                getTrayId(tray.getId()),
                false,
                List.of(buildAmsFilament(getTrayId(tray.getId())))
        ));
    }

    private Div buildAms() {
        final Div result = new Div();
        result.addClassName("filaments");
        printer.getFullStatus().ifPresent(m -> {
            if (!m.message().hasPrint() || !m.message().getPrint().hasAms()) {
                return;
            }
            if (m.message().getPrint().getAms().getAmsCount() > 0) {
                buildAms(result, m.message().getPrint().getAms());
                return;
            }
            if (m.message().getPrint().hasVtTray()) {
                buildVtTray(result, m.message().getPrint().getVtTray());
            }
        });
        return result;
    }

    public Component build() {
        try {
            return createContent(
                    wrapNameMenu(buildName()),
                    buildImage(),
                    buildStatus(),
                    buildAms(),
                    buildProgressBar(),
                    progressBar
            //,statusBox
            );
        } finally {
            built = true;
        }
    }

    private Component createContent(final Component... components) {
        final VerticalLayout content = new VerticalLayout();
        content.addClassName("dashboard-printer");
        content.setPadding(false);
        content.setSpacing(false);
        content.add(components);
        content.setSizeUndefined();
        return content;
    }

    private record AmsHeader(String id, Span temperature, Image humidity) {

    }

    private record AmsFilament(String id, Span type, Span color) {

    }

    private enum Images {
        AMS_HUMIDITY_0("ams_humidity_0.svg"),
        AMS_HUMIDITY_1("ams_humidity_1.svg"),
        AMS_HUMIDITY_2("ams_humidity_2.svg"),
        AMS_HUMIDITY_3("ams_humidity_3.svg"),
        AMS_HUMIDITY_4("ams_humidity_4.svg"),
        MONITOR_BED_TEMP("monitor_bed_temp.svg"),
        MONITOR_BED_TEMP_ACTIVE("monitor_bed_temp_active.svg"),
        MONITOR_NOZZLE_TEMP("monitor_nozzle_temp.svg"),
        MONITOR_NOZZLE_TEMP_ACTIVE("monitor_nozzle_temp_active.svg"),
        MONITOR_SPEED("monitor_speed.svg"),
        MONITOR_SPEED_ACTIVE("monitor_speed_active.svg"),
        MONITOR_FRAME_TEMP("monitor_frame_temp.svg"),
        MONITOR_LAMP_ON("monitor_lamp_on.svg"),
        MONITOR_LAMP_OFF("monitor_lamp_off.svg");

        private final String image;

        private Images(final String image) {
            this.image = "bambu/%s".formatted(image);
        }

        public String getImage() {
            return image;
        }

    }

}
