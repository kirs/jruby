/*
 * Copyright (c) 2013, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.kernel;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.core.Layouts;
import org.jruby.truffle.core.string.StringOperations;
import org.jruby.truffle.language.RubyGuards;
import org.jruby.truffle.language.arguments.RubyArguments;
import org.jruby.truffle.language.loader.SourceLoader;
import org.jruby.truffle.language.yield.YieldNode;

import java.util.ArrayList;
import java.util.Collection;

public class TraceManager {

    public static final String LINE_TAG = "org.jruby.truffle.trace.line";
    public static final String CLASS_TAG = "org.jruby.truffle.trace.class";
    public static final String CALL_TAG = "org.jruby.truffle.trace.call";

    private final RubyContext context;
    private final Instrumenter instrumenter;

    private Collection<EventBinding<?>> instruments;
    private boolean isInTraceFunc = false;

    public TraceManager(RubyContext context, Instrumenter instrumenter) {
        this.context = context;
        this.instrumenter = instrumenter;
    }

    public void setTraceFunc(final DynamicObject traceFunc) {
        assert RubyGuards.isRubyProc(traceFunc);

        if (instruments != null) {
            for (EventBinding<?> instrument : instruments) {
                instrument.dispose();
            }
        }

        if (traceFunc == null) {
            instruments = null;
            return;
        }

        instruments = new ArrayList<>();

        instruments.add(instrumenter.attachFactory(SourceSectionFilter.newBuilder().tagIs(LINE_TAG).build(), new ExecutionEventNodeFactory() {
            @Override
            public ExecutionEventNode create(EventContext eventContext) {
                return new BaseEventEventNode(context, traceFunc, context.getCoreStrings().LINE.createInstance());
            }
        }));

        instruments.add(instrumenter.attachFactory(SourceSectionFilter.newBuilder().tagIs(CALL_TAG).build(), new ExecutionEventNodeFactory() {
            @Override
            public ExecutionEventNode create(EventContext eventContext) {
                return new CallEventEventNode(context, traceFunc, context.getCoreStrings().CALL.createInstance());
            }
        }));

        instruments.add(instrumenter.attachFactory(SourceSectionFilter.newBuilder().tagIs(CLASS_TAG).build(), new ExecutionEventNodeFactory() {
            @Override
            public ExecutionEventNode create(EventContext eventContext) {
                return new BaseEventEventNode(context, traceFunc, context.getCoreStrings().CLASS.createInstance());
            }
        }));

    }

    private class BaseEventEventNode extends ExecutionEventNode {

        protected final ConditionProfile inTraceFuncProfile = ConditionProfile.createBinaryProfile();

        protected final RubyContext context;
        protected final DynamicObject traceFunc;
        protected final Object event;

        @Child private YieldNode yieldNode;

        @CompilationFinal private DynamicObject file;
        @CompilationFinal private int line;

        public BaseEventEventNode(RubyContext context, DynamicObject traceFunc, Object event) {
            this.context = context;
            this.traceFunc = traceFunc;
            this.event = event;
        }

        @Override
        protected void onEnter(VirtualFrame frame) {
            if (!inTraceFuncProfile.profile(isInTraceFunc)) {
                final DynamicObject binding = Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), frame.materialize());

                isInTraceFunc = true;

                try {
                    getYieldNode().dispatch(frame, traceFunc,
                            event,
                            getFile(),
                            getLine(),
                            context.getCoreLibrary().getNilObject(),
                            binding,
                            context.getCoreLibrary().getNilObject());
                } finally {
                   isInTraceFunc = false;
                }
            }
        }

        protected DynamicObject getFile() {
            if (file == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                file = StringOperations.createString(context, context.getRopeTable().getRopeUTF8(getEncapsulatingSourceSection().getSource().getName()));
            }

            return file;
        }

        protected int getLine() {
            if (line == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                line = getEncapsulatingSourceSection().getStartLine();
            }

            return line;
        }

        protected YieldNode getYieldNode() {
            if (yieldNode == null) {
                CompilerDirectives.transferToInterpreter();
                yieldNode = insert(new YieldNode(context));
            }

            return yieldNode;
        }

    }

    private class CallEventEventNode extends BaseEventEventNode {

        private final static String callTraceFuncCode = "traceFunc.call(event, file, line, id, binding, classname)";

        public CallEventEventNode(RubyContext context, DynamicObject traceFunc, Object event) {
            super(context, traceFunc, event);
        }

        @Override
        protected void onEnter(VirtualFrame frame) {
            if (!inTraceFuncProfile.profile(isInTraceFunc)) {
                callSetTraceFunc(frame.materialize());
            }
        }

        @TruffleBoundary
        private void callSetTraceFunc(MaterializedFrame frame) {
            // set_trace_func reports the file and line of the call site.
            final String filename;
            final int line;
            final SourceSection sourceSection = Truffle.getRuntime().getCallerFrame().getCallNode().getEncapsulatingSourceSection();

            if (sourceSection.getSource() != null) {
                // Skip over any lines that are a result of the trace function call being made.
                if (sourceSection.getSource().getCode().equals(callTraceFuncCode)) {
                    return;
                }

                filename = sourceSection.getSource().getName();
                line = sourceSection.getStartLine();
            } else {
                filename = "<internal>";
                line = -1;
            }

            if (!context.getOptions().INCLUDE_CORE_FILE_CALLERS_IN_SET_TRACE_FUNC && filename.startsWith(SourceLoader.TRUFFLE_SCHEME)) {
                return;
            }

            final Object self = RubyArguments.getSelf(frame);
            final Object classname = context.getCoreLibrary().getLogicalClass(self);
            final Object id = context.getSymbolTable().getSymbol(RubyArguments.getMethod(frame).getName());

            final DynamicObject binding = Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), Truffle.getRuntime().getCallerFrame().getFrame(FrameInstance.FrameAccess.MATERIALIZE, true).materialize());

            isInTraceFunc = true;
            try {
                context.getCodeLoader().inline(this, frame, callTraceFuncCode, "traceFunc", traceFunc, "event", event, "file", filename, "line", line, "id", id, "binding", binding, "classname", classname);
            } finally {
                isInTraceFunc = false;
            }
        }

    }

}
