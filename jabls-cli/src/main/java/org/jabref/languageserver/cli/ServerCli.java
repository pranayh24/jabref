package org.jabref.languageserver.cli;

import java.util.concurrent.Callable;

import org.jabref.architecture.AllowedToUseStandardStreams;
import org.jabref.languageserver.LSPLauncher;
import org.jabref.logic.preferences.JabRefCliPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import picocli.CommandLine;

@AllowedToUseStandardStreams("This is a CLI application. It resides in the package languageserver.server to be close to the other languageserver related classes.")
@CommandLine.Command(name = "languageserver", mixinStandardHelpOptions = true, description = "JabLS - JabRef LanguageServer")
public class ServerCli implements Callable<Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerCli.class);

    public static void main(final String[] args) throws InterruptedException {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        new CommandLine(new ServerCli()).execute(args);
    }

    @Override
    public Void call() throws InterruptedException {
        LSPLauncher lspLauncher = new LSPLauncher();
        lspLauncher.run(JabRefCliPreferences.getInstance());

        // Keep the server running until user kills the process (e.g., presses Ctrl+C)
        Thread.currentThread().join();

        return null;
    }
}
