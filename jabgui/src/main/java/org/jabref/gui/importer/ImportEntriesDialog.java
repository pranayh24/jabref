package org.jabref.gui.importer;

import java.util.Optional;

import javax.swing.undo.UndoManager;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import org.jabref.gui.DialogService;
import org.jabref.gui.StateManager;
import org.jabref.gui.entryeditor.citationrelationtab.BibEntryView;
import org.jabref.gui.icon.IconTheme;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.gui.util.BaseDialog;
import org.jabref.gui.util.NoSelectionModel;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.shared.DatabaseLocation;
import org.jabref.logic.util.BackgroundTask;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.util.FileUpdateMonitor;

import com.airhacks.afterburner.views.ViewLoader;
import com.tobiasdiez.easybind.EasyBind;
import jakarta.inject.Inject;
import org.controlsfx.control.CheckListView;
import org.fxmisc.richtext.CodeArea;

public class ImportEntriesDialog extends BaseDialog<Boolean> {

    @FXML private CheckListView<BibEntry> entriesListView;
    @FXML private ComboBox<BibDatabaseContext> libraryListView;
    @FXML private ButtonType importButton;
    @FXML private Label totalItems;
    @FXML private Label selectedItems;
    @FXML private Label bibTeXDataLabel;
    @FXML private CheckBox downloadLinkedOnlineFiles;
    @FXML private CheckBox showEntryInformation;
    @FXML private CodeArea bibTeXData;
    @FXML private VBox bibTeXDataBox;

    private final BackgroundTask<ParserResult> task;
    private final BibDatabaseContext database;
    private ImportEntriesViewModel viewModel;

    @Inject private TaskExecutor taskExecutor;
    @Inject private DialogService dialogService;
    @Inject private UndoManager undoManager;
    @Inject private GuiPreferences preferences;
    @Inject private StateManager stateManager;
    @Inject private BibEntryTypesManager entryTypesManager;
    @Inject private FileUpdateMonitor fileUpdateMonitor;

    /**
     * Imports the given entries into the given database. The entries are provided using the BackgroundTask
     *
     * @param database the database to import into
     * @param task     the task executed for parsing the selected files(s).
     */
    public ImportEntriesDialog(BibDatabaseContext database, BackgroundTask<ParserResult> task) {
        this.database = database;
        this.task = task;
        ViewLoader.view(this)
                  .load()
                  .setAsDialogPane(this);

        BooleanBinding booleanBind = Bindings.isEmpty(entriesListView.getCheckModel().getCheckedItems());
        Button btn = (Button) this.getDialogPane().lookupButton(importButton);
        btn.disableProperty().bind(booleanBind);

        downloadLinkedOnlineFiles.setSelected(preferences.getFilePreferences().shouldDownloadLinkedFiles());

        setResultConverter(button -> {
            if (button == importButton) {
                viewModel.importEntries(entriesListView.getCheckModel().getCheckedItems(), downloadLinkedOnlineFiles.isSelected());
            } else {
                dialogService.notify(Localization.lang("Import canceled"));
            }

            return false;
        });
    }

    @FXML
    private void initialize() {
        viewModel = new ImportEntriesViewModel(task, taskExecutor, database, dialogService, undoManager, preferences, stateManager, entryTypesManager, fileUpdateMonitor);
        Label placeholder = new Label();
        placeholder.textProperty().bind(viewModel.messageProperty());
        entriesListView.setPlaceholder(placeholder);
        entriesListView.setItems(viewModel.getEntries());

        libraryListView.setEditable(false);
        libraryListView.getItems().addAll(stateManager.getOpenDatabases());
        new ViewModelListCellFactory<BibDatabaseContext>()
                .withText(database -> {
                    Optional<String> dbOpt = Optional.empty();
                    if (database.getDatabasePath().isPresent()) {
                        dbOpt = FileUtil.getUniquePathFragment(stateManager.getAllDatabasePaths(), database.getDatabasePath().get());
                    }
                    if (database.getLocation() == DatabaseLocation.SHARED) {
                        return database.getDBMSSynchronizer().getDBName() + " [" + Localization.lang("shared") + "]";
                    }

                    return dbOpt.orElseGet(() -> Localization.lang("untitled"));
                })
                .install(libraryListView);
        viewModel.selectedDbProperty().bind(libraryListView.getSelectionModel().selectedItemProperty());
        stateManager.getActiveDatabase().ifPresent(database1 -> libraryListView.getSelectionModel().select(database1));

        PseudoClass entrySelected = PseudoClass.getPseudoClass("selected");
        new ViewModelListCellFactory<BibEntry>()
                .withGraphic(entry -> {
                    ToggleButton addToggle = IconTheme.JabRefIcons.ADD.asToggleButton();
                    EasyBind.subscribe(addToggle.selectedProperty(), selected -> {
                        if (selected) {
                            addToggle.setGraphic(IconTheme.JabRefIcons.ADD_FILLED.withColor(IconTheme.SELECTED_COLOR).getGraphicNode());
                        } else {
                            addToggle.setGraphic(IconTheme.JabRefIcons.ADD.getGraphicNode());
                        }
                    });
                    addToggle.getStyleClass().add("addEntryButton");
                    addToggle.selectedProperty().bindBidirectional(entriesListView.getItemBooleanProperty(entry));
                    HBox separator = new HBox();
                    HBox.setHgrow(separator, Priority.SOMETIMES);
                    Node entryNode = BibEntryView.getEntryNode(entry);
                    HBox.setHgrow(entryNode, Priority.ALWAYS);
                    HBox container = new HBox(entryNode, separator, addToggle);
                    container.getStyleClass().add("entry-container");
                    container.prefWidthProperty().bind(entriesListView.widthProperty().subtract(25));

                    BackgroundTask.wrap(() -> viewModel.hasDuplicate(entry)).onSuccess(duplicateFound -> {
                        if (duplicateFound) {
                            Node icon = IconTheme.JabRefIcons.ERROR.getGraphicNode();
                            Tooltip tooltip = new Tooltip(Localization.lang("Possible duplicate of existing entry. Will be resolved on import."));
                            Tooltip.install(icon, tooltip);
                            container.getChildren().add(icon);
                        }
                    }).executeWith(taskExecutor);

                    /*
                    inserted the if-statement here, since a Platform.runLater() call did not work.
                    also tried to move it to the end of the initialize method, but it did not select the entry.
                    */
                    if (entriesListView.getItems().size() == 1) {
                        selectAllNewEntries();
                    }

                    return container;
                })
                .withOnMouseClickedEvent((entry, event) -> {
                    entriesListView.getCheckModel().toggleCheckState(entry);
                    displayBibTeX(entry, viewModel.getSourceString(entry));
                })
                .withPseudoClass(entrySelected, entriesListView::getItemBooleanProperty)
                .install(entriesListView);

        selectedItems.textProperty().bind(Bindings.size(entriesListView.getCheckModel().getCheckedItems()).asString());
        totalItems.textProperty().bind(Bindings.size(entriesListView.getItems()).asString());
        entriesListView.setSelectionModel(new NoSelectionModel<>());
        initBibTeX();
    }

    private void displayBibTeX(BibEntry entry, String bibTeX) {
        if (entriesListView.getCheckModel().isChecked(entry)) {
            bibTeXData.clear();
            bibTeXData.appendText(bibTeX);
            bibTeXData.moveTo(0);
            bibTeXData.requestFollowCaret();
        } else {
            bibTeXData.clear();
        }
    }

    private void initBibTeX() {
        bibTeXDataLabel.setText(Localization.lang("%0 source", "BibTeX"));
        bibTeXData.setBorder(new Border(new BorderStroke(Color.GREY, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        bibTeXData.setPadding(new Insets(5.0));
        showEntryInformation.selectedProperty().addListener((observableValue, old_val, new_val) -> {
            bibTeXDataBox.setVisible(new_val);
            bibTeXDataBox.setManaged(new_val);
        });
    }

    public void unselectAll() {
        entriesListView.getCheckModel().clearChecks();
    }

    public void selectAllNewEntries() {
        unselectAll();
        for (BibEntry entry : entriesListView.getItems()) {
            if (!viewModel.hasDuplicate(entry)) {
                entriesListView.getCheckModel().check(entry);
                displayBibTeX(entry, viewModel.getSourceString(entry));
            }
        }
    }

    public void selectAllEntries() {
        unselectAll();
        entriesListView.getCheckModel().checkAll();
    }
}
