package org.jabref.gui.shared;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import javax.swing.undo.UndoManager;

import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

import org.jabref.gui.ClipBoardManager;
import org.jabref.gui.DialogService;
import org.jabref.gui.LibraryTab;
import org.jabref.gui.LibraryTabContainer;
import org.jabref.gui.StateManager;
import org.jabref.gui.exporter.SaveDatabaseAction;
import org.jabref.gui.mergeentries.threewaymerge.EntriesMergeResult;
import org.jabref.gui.mergeentries.threewaymerge.MergeEntriesDialog;
import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.gui.undo.UndoableRemoveEntries;
import org.jabref.logic.ai.AiService;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.shared.DBMSConnection;
import org.jabref.logic.shared.DBMSConnectionProperties;
import org.jabref.logic.shared.DBMSSynchronizer;
import org.jabref.logic.shared.DatabaseNotSupportedException;
import org.jabref.logic.shared.DatabaseSynchronizer;
import org.jabref.logic.shared.event.ConnectionLostEvent;
import org.jabref.logic.shared.event.SharedEntriesNotPresentEvent;
import org.jabref.logic.shared.event.UpdateRefusedEvent;
import org.jabref.logic.shared.exception.InvalidDBMSConnectionPropertiesException;
import org.jabref.logic.shared.exception.NotASharedDatabaseException;
import org.jabref.logic.shared.prefs.SharedDatabasePreferences;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.util.FileUpdateMonitor;

import com.google.common.eventbus.Subscribe;

public class SharedDatabaseUIManager {

    private final LibraryTabContainer tabContainer;
    private DatabaseSynchronizer dbmsSynchronizer;
    private final DialogService dialogService;
    private final GuiPreferences preferences;
    private final AiService aiService;
    private final StateManager stateManager;
    private final BibEntryTypesManager entryTypesManager;
    private final FileUpdateMonitor fileUpdateMonitor;
    private final UndoManager undoManager;
    private final ClipBoardManager clipBoardManager;
    private final TaskExecutor taskExecutor;

    public SharedDatabaseUIManager(LibraryTabContainer tabContainer,
                                   DialogService dialogService,
                                   GuiPreferences preferences,
                                   AiService aiService,
                                   StateManager stateManager,
                                   BibEntryTypesManager entryTypesManager,
                                   FileUpdateMonitor fileUpdateMonitor,
                                   UndoManager undoManager,
                                   ClipBoardManager clipBoardManager,
                                   TaskExecutor taskExecutor) {
        this.tabContainer = tabContainer;
        this.dialogService = dialogService;
        this.preferences = preferences;
        this.aiService = aiService;
        this.stateManager = stateManager;
        this.entryTypesManager = entryTypesManager;
        this.fileUpdateMonitor = fileUpdateMonitor;
        this.undoManager = undoManager;
        this.clipBoardManager = clipBoardManager;
        this.taskExecutor = taskExecutor;
    }

    @Subscribe
    public void listen(ConnectionLostEvent connectionLostEvent) {
        ButtonType reconnect = new ButtonType(Localization.lang("Reconnect"), ButtonData.YES);
        ButtonType workOffline = new ButtonType(Localization.lang("Work offline"), ButtonData.NO);
        ButtonType closeLibrary = new ButtonType(Localization.lang("Close library"), ButtonData.CANCEL_CLOSE);

        Optional<ButtonType> answer = dialogService.showCustomButtonDialogAndWait(AlertType.WARNING,
                Localization.lang("Connection lost"),
                Localization.lang("The connection to the server has been terminated."),
                reconnect,
                workOffline,
                closeLibrary);

        if (answer.isPresent()) {
            if (answer.get().equals(reconnect)) {
                tabContainer.closeTab(tabContainer.getCurrentLibraryTab());
                dialogService.showCustomDialogAndWait(new SharedDatabaseLoginDialogView(tabContainer));
            } else if (answer.get().equals(workOffline)) {
                connectionLostEvent.bibDatabaseContext().convertToLocalDatabase();
                tabContainer.getLibraryTabs().forEach(tab -> tab.updateTabTitle(tab.isModified()));
                dialogService.notify(Localization.lang("Working offline."));
            }
        } else {
            tabContainer.closeTab(tabContainer.getCurrentLibraryTab());
        }
    }

    @Subscribe
    public void listen(UpdateRefusedEvent updateRefusedEvent) {
        dialogService.notify(Localization.lang("Update refused."));

        BibEntry localBibEntry = updateRefusedEvent.localBibEntry();
        BibEntry sharedBibEntry = updateRefusedEvent.sharedBibEntry();

        String message = Localization.lang("Update could not be performed due to existing change conflicts.") + "\r\n" +
                Localization.lang("You are not working on the newest version of the entry.") + "\r\n" +
                Localization.lang("Shared version: %0", String.valueOf(sharedBibEntry.getSharedBibEntryData().getVersion())) + "\r\n" +
                Localization.lang("Local version: %0", String.valueOf(localBibEntry.getSharedBibEntryData().getVersion())) + "\r\n" +
                Localization.lang("Press \"Merge entries\" to merge the changes and resolve this problem.") + "\r\n" +
                Localization.lang("Canceling this operation will leave your changes unsynchronized.");

        ButtonType merge = new ButtonType(Localization.lang("Merge entries"), ButtonBar.ButtonData.YES);

        Optional<ButtonType> response = dialogService.showCustomButtonDialogAndWait(AlertType.CONFIRMATION, Localization.lang("Update refused"), message, ButtonType.CANCEL, merge);

        if (response.isPresent() && response.get().equals(merge)) {
            MergeEntriesDialog dialog = new MergeEntriesDialog(localBibEntry, sharedBibEntry, preferences);
            dialog.setTitle(Localization.lang("Update refused"));
            Optional<BibEntry> mergedEntry = dialogService.showCustomDialogAndWait(dialog).map(EntriesMergeResult::mergedEntry);

            mergedEntry.ifPresent(mergedBibEntry -> {
                mergedBibEntry.getSharedBibEntryData().setSharedID(sharedBibEntry.getSharedBibEntryData().getSharedID());
                mergedBibEntry.getSharedBibEntryData().setVersion(sharedBibEntry.getSharedBibEntryData().getVersion());

                dbmsSynchronizer.synchronizeSharedEntry(mergedBibEntry);
                dbmsSynchronizer.synchronizeLocalDatabase();
            });
        }
    }

    @Subscribe
    public void listen(SharedEntriesNotPresentEvent event) {
        LibraryTab libraryTab = tabContainer.getCurrentLibraryTab();

        if (libraryTab != null) {
            undoManager.addEdit(new UndoableRemoveEntries(libraryTab.getDatabase(), event.bibEntries()));

            dialogService.showInformationDialogAndWait(Localization.lang("Shared entry is no longer present"),
                    Localization.lang("The entry you currently work on has been deleted on the shared side.")
                            + "\n"
                            + Localization.lang("You can restore the entry using the \"Undo\" operation."));

            stateManager.setSelectedEntries(List.of());
        }
    }

    /**
     * Opens a new shared database tab with the given {@link DBMSConnectionProperties}.
     *
     * @param dbmsConnectionProperties Connection data
     * @return BasePanel which also used by {@link SaveDatabaseAction}
     */
    public LibraryTab openNewSharedDatabaseTab(DBMSConnectionProperties dbmsConnectionProperties)
            throws SQLException, DatabaseNotSupportedException, InvalidDBMSConnectionPropertiesException {

        BibDatabaseContext bibDatabaseContext = getBibDatabaseContextForSharedDatabase();

        dbmsSynchronizer = bibDatabaseContext.getDBMSSynchronizer();
        dbmsSynchronizer.openSharedDatabase(new DBMSConnection(dbmsConnectionProperties));
        dbmsSynchronizer.registerListener(this);
        dialogService.notify(Localization.lang("Connection to %0 server established.", dbmsConnectionProperties.getType().toString()));

        LibraryTab libraryTab = LibraryTab.createLibraryTab(
                bibDatabaseContext,
                tabContainer,
                dialogService,
                aiService,
                preferences,
                stateManager,
                fileUpdateMonitor,
                entryTypesManager,
                undoManager,
                clipBoardManager,
                taskExecutor);
        tabContainer.addTab(libraryTab, true);
        return libraryTab;
    }

    public void openSharedDatabaseFromParserResult(ParserResult parserResult)
            throws SQLException, DatabaseNotSupportedException, InvalidDBMSConnectionPropertiesException,
            NotASharedDatabaseException {

        Optional<String> sharedDatabaseIDOptional = parserResult.getDatabase().getSharedDatabaseID();

        if (sharedDatabaseIDOptional.isEmpty()) {
            throw new NotASharedDatabaseException();
        }

        String sharedDatabaseID = sharedDatabaseIDOptional.get();
        DBMSConnectionProperties dbmsConnectionProperties = new DBMSConnectionProperties(new SharedDatabasePreferences(sharedDatabaseID));

        BibDatabaseContext bibDatabaseContext = getBibDatabaseContextForSharedDatabase();

        bibDatabaseContext.getDatabase().setSharedDatabaseID(sharedDatabaseID);
        bibDatabaseContext.setDatabasePath(parserResult.getDatabaseContext().getDatabasePath().orElse(null));

        dbmsSynchronizer = bibDatabaseContext.getDBMSSynchronizer();
        dbmsSynchronizer.openSharedDatabase(new DBMSConnection(dbmsConnectionProperties));
        dbmsSynchronizer.registerListener(this);
        dialogService.notify(Localization.lang("Connection to %0 server established.", dbmsConnectionProperties.getType().toString()));

        parserResult.setDatabaseContext(bibDatabaseContext);
    }

    private BibDatabaseContext getBibDatabaseContextForSharedDatabase() {
        BibDatabaseContext bibDatabaseContext = new BibDatabaseContext();
        bibDatabaseContext.setMode(preferences.getLibraryPreferences().getDefaultBibDatabaseMode());
        DBMSSynchronizer synchronizer = new DBMSSynchronizer(
                bibDatabaseContext,
                preferences.getBibEntryPreferences().getKeywordSeparator(),
                preferences.getFieldPreferences(),
                preferences.getCitationKeyPatternPreferences().getKeyPatterns(),
                fileUpdateMonitor);
        bibDatabaseContext.convertToSharedDatabase(synchronizer);
        return bibDatabaseContext;
    }
}
