/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game.spiralknights.hook;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class ConsoleLogMirrorHook {
	public static final String INTERNAL_NAME = ConsoleLogMirrorHook.class.getName().replace('.', '/');

	private ConsoleLogMirrorHook() {
	}

	private static PrintStream createTerminalStream(FileDescriptor descriptor) {
		return new PrintStream(new FileOutputStream(descriptor), true);
	}

	public static synchronized void installConsoleLogMirror() {
		installConsoleLogMirror(
				createTerminalStream(FileDescriptor.out),
				createTerminalStream(FileDescriptor.err));
	}

	private static boolean isInstalled() {
		return System.out instanceof MirroredPrintStream && System.err instanceof MirroredPrintStream;
	}

	private static synchronized void installConsoleLogMirror(PrintStream terminalOut, PrintStream terminalErr) {
		//TODO: Document no_log_redir
		if (Boolean.getBoolean("no_log_redir") || isInstalled()) {
			return;
		}

		//The currently modified logs of the game.
		PrintStream logOut = System.out;
		PrintStream logErr = System.err;

		//Reset System with our logging machinery
		System.setOut(new MirroredPrintStream(logOut, terminalOut));
		System.setErr(new MirroredPrintStream(logErr, terminalErr));
		mirrorJulToTerminal(terminalErr);
	}

	private static void mirrorJulToTerminal(PrintStream terminalErr) {
		Logger rootLogger = LogManager.getLogManager().getLogger("");
		if (rootLogger == null) return;

		removeExistingJulMirror(rootLogger);

		Handler mirrorHandler = new TerminalLogHandler(terminalErr);
		Handler[] handlers = rootLogger.getHandlers();

		if (handlers.length > 0) {
			Formatter formatter = handlers[0].getFormatter();

			if (formatter != null) {
				mirrorHandler.setFormatter(formatter);
			} else {
				mirrorHandler.setFormatter(new SimpleFormatter());
			}
		}

		rootLogger.addHandler(mirrorHandler);
	}

	private static void removeExistingJulMirror(Logger rootLogger) {
		for (Handler handler : rootLogger.getHandlers()) {
			if (handler instanceof TerminalLogHandler) {
				rootLogger.removeHandler(handler);
				handler.close();
			}
		}
	}

	private static final class TerminalLogHandler extends Handler {
		private final PrintStream stream;

		TerminalLogHandler(PrintStream stream) {
			this.stream = stream;
		}

		@Override
		public void publish(LogRecord record) {
			if (!isLoggable(record)) return;

			Formatter formatter = getFormatter();
			String message;

			synchronized (formatter) {
				message = formatter.format(record);
			}

			//FIXME: Maybe remove this? SK spams this a lot :(
			if (record.getLevel() == Level.FINE && (message.contains("ClipManager") || message.contains("Previous message repeated"))) {
				return;
			}

			stream.print(message);
			stream.flush();
		}

		@Override
		public void flush() {
			stream.flush();
		}

		@Override
		public void close() {
			flush();
		}
	}

	private static final class MirroredPrintStream extends PrintStream {
		MirroredPrintStream(OutputStream... streams) {
			super(new TeeOutputStream(streams), true);
		}
	}

	private static final class TeeOutputStream extends OutputStream {
		private final OutputStream[] streams;

		TeeOutputStream(OutputStream... streams) {
			this.streams = streams.clone();
		}

		@Override
		public void write(int value) throws IOException {
			for (OutputStream stream : streams) {
				stream.write(value);
			}
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			for (OutputStream stream : streams) {
				stream.write(buffer, offset, length);
			}
		}

		@Override
		public void flush() throws IOException {
			for (OutputStream stream : streams) {
				stream.flush();
			}
		}
	}
}
