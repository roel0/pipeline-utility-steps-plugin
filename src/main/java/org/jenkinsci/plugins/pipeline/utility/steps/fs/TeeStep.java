/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.utility.steps.fs;

import hudson.Extension;
import hudson.FilePath;
import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Set;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

public class TeeStep extends Step implements Serializable {

    public final String file;
    private static final long serialVersionUID = 1;

    @DataBoundConstructor
    public TeeStep(String file) {
        this.file = file;
    }
    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, file);
    }

    private static class Execution extends StepExecution {

        private final String file;
        private TeeFilter tee;

        Execution(StepContext context, String file) {
            super(context);
            this.file = file;
        }

        @Override
        public boolean start() throws Exception {
            FilePath f = getContext().get(FilePath.class).child(file);
            getContext().newBodyInvoker().
                withContext(BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), new TeeFilter(f))).
                withCallback(BodyExecutionCallback.wrap(getContext())).
                start();
            return false;
        }

        private static final long serialVersionUID = 1;

    }
    private static class TeeFilter extends ConsoleLogFilter implements Serializable {

        private final FilePath f;
        private transient OutputStream stream = null;

        TeeFilter(FilePath f) {
            this.f = f;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public OutputStream decorateLogger(Run build, final OutputStream logger) throws IOException, InterruptedException {
            return new TeeOutputStream(logger, new UglyStream(f));
        }

        static class UglyStream extends OutputStream {
            /** The delegate output stream. */
            private final FilePath delegate;

            private static final class TeeFile extends MasterToSlaveFileCallable<OutputStream> {
                @Override
                public OutputStream invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                    f = f.getAbsoluteFile();
                    if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
                        throw new IOException("Failed to create directory " + f.getParentFile());
                    }
                    try {
                        return new RemoteOutputStream(Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE));
                    } catch (InvalidPathException e) {
                        throw new IOException(e);
                    }
                }
                private static final long serialVersionUID = 1;
            }

            /** @see FilePath#write() */
            private OutputStream append(FilePath fp) throws IOException, InterruptedException {
                if (fp.getChannel() == null) {
                    File f = new File(fp.getRemote()).getAbsoluteFile();
                    if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
                        throw new IOException("Failed to create directory " + f.getParentFile());
                    }
                    try {
                        return Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                    } catch (InvalidPathException e) {
                        throw new IOException(e);
                    }
                } else {
                    return fp.act(new TeeFile());
                }
            }

            UglyStream (FilePath delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(int b) throws IOException {
                try {
                    OutputStream temp = append(delegate);
                    temp.write(b);
                    temp.close();
                } catch (InterruptedException e) {
                }
            }

            /** {@inheritDoc} */
            @Override
            public void write(byte[] b) throws IOException {
                try {
                    OutputStream temp = append(delegate);
                    temp.write(b);
                    temp.close();
                } catch (InterruptedException e) {
                }
            }

            /** {@inheritDoc} */
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    OutputStream temp = append(delegate);
                    temp.write(b, off, len);
                    temp.close();
                } catch (InterruptedException e) {
                }
            }

            @Override
            public void flush() throws IOException {
            }

            @Override
            public void close() throws IOException {
            }
        }

        private static final long serialVersionUID = 1;

    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(FilePath.class);
        }

        @Override
        public String getFunctionName() {
            return "tee";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Tee output to file";
        }
    }
}
