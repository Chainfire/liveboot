/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libsuperuser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * Thread utility class continuously reading from an InputStream
 */
@SuppressWarnings({"WeakerAccess"})
public class StreamGobbler extends Thread {
    private static int threadCounter = 0;
    private static int incThreadCounter() {
        synchronized (StreamGobbler.class) {
            int ret = threadCounter;
            threadCounter++;
            return ret;
        }
    }

    /**
     * Line callback interface
     */
    public interface OnLineListener {
        /**
         * <p>Line callback</p>
         *
         * <p>This callback should process the line as quickly as possible.
         * Delays in this callback may pause the native process or even
         * result in a deadlock</p>
         *
         * @param line String that was gobbled
         */
        void onLine(String line);
    }

    /**
     * Stream closed callback interface
     */
    public interface OnStreamClosedListener {
        /**
         * <p>Stream closed callback</p>
         */
        void onStreamClosed();
    }

    @NonNull
    private final String shell;
    @NonNull
    private final InputStream inputStream;
    @NonNull
    private final BufferedReader reader;
    @Nullable
    private final List<String> writer;
    @Nullable
    private final OnLineListener lineListener;
    @Nullable
    private final OnStreamClosedListener streamClosedListener;
    private volatile boolean active = true;
    private volatile boolean calledOnClose = false;

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputList {@literal List<String>} to write to, or null
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream, @Nullable List<String> outputList) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        writer = outputList;
        lineListener = null;
        streamClosedListener = null;
    }

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param onLineListener OnLineListener callback
     * @param onStreamClosedListener OnStreamClosedListener callback
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream, @Nullable OnLineListener onLineListener, @Nullable OnStreamClosedListener onStreamClosedListener) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        lineListener = onLineListener;
        streamClosedListener = onStreamClosedListener;
        writer = null;
    }

    @Override
    public void run() {
        // keep reading the InputStream until it ends (or an error occurs)
        // optionally pausing when a command is executed that consumes the InputStream itself
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Debug.logOutput(String.format(Locale.ENGLISH, "[%s] %s", shell, line));
                if (writer != null) writer.add(line);
                if (lineListener != null) lineListener.onLine(line);
                while (!active) {
                    synchronized (this) {
                        try {
                            this.wait(128);
                        } catch (InterruptedException e) {
                            // no action
                        }
                    }
                }
            }
        } catch (IOException e) {
            // reader probably closed, expected exit condition
            if (streamClosedListener != null) {
                calledOnClose = true;
                streamClosedListener.onStreamClosed();
            }
        }

        // make sure our stream is closed and resources will be freed
        try {
            reader.close();
        } catch (IOException e) {
            // read already closed
        }

        if (!calledOnClose) {
            if (streamClosedListener != null) {
                calledOnClose = true;
                streamClosedListener.onStreamClosed();
            }
        }
    }

    /**
     * <p>Resume consuming the input from the stream</p>
     */
    @AnyThread
    public void resumeGobbling() {
        if (!active) {
            synchronized (this) {
                active = true;
                this.notifyAll();
            }
        }
    }

    /**
     * <p>Suspend gobbling, so other code may read from the InputStream instead</p>
     *
     * <p>This should <i>only</i> be called from the OnLineListener callback!</p>
     */
    @AnyThread
    public void suspendGobbling() {
        synchronized (this) {
            active = false;
            this.notifyAll();
        }
    }

    /**
     * <p>Wait for gobbling to be suspended</p>
     *
     * <p>Obviously this cannot be called from the same thread as {@link #suspendGobbling()}</p>
     */
    @WorkerThread
    public void waitForSuspend() {
        synchronized (this) {
            while (active) {
                try {
                    this.wait(32);
                } catch (InterruptedException e) {
                    // no action
                }
            }
        }
    }

    /**
     * <p>Is gobbling suspended ?</p>
     *
     * @return is gobbling suspended?
     */
    @AnyThread
    public boolean isSuspended() {
        synchronized (this) {
            return !active;
        }
    }

    /**
     * <p>Get current source InputStream</p>
     *
     * @return source InputStream
     */
    @NonNull
    @AnyThread
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * <p>Get current OnLineListener</p>
     *
     * @return OnLineListener
     */
    @Nullable
    @AnyThread
    public OnLineListener getOnLineListener() {
        return lineListener;
    }

    void conditionalJoin() throws InterruptedException {
        if (calledOnClose) return; // deadlock from callback, we're inside exit procedure
        if (Thread.currentThread() == this) return; // can't join self
        join();
    }
}
