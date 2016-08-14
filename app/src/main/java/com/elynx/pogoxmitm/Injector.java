package com.elynx.pogoxmitm;

import java.net.HttpURLConnection;
import java.nio.ByteBuffer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * Class that manages injection of code into target app
 */
public class Injector implements IXposedHookLoadPackage {
    private static String[] Methods = {"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "TRACE"};

    private static class RpcContext {
        long threadId = 0L;

        long objectId = 0L;
        int requestId = 0;
        String url;
        String method;
        String requestHeaders;
        String responseHeaders;
        int responseCode = 0;

        public String shortDump() {
            StringBuilder builder = new StringBuilder();

            builder.append(Integer.toString(requestId));
            //builder.append(":");
            //builder.append(Long.toHexString(objectId));
            builder.append(" ");
            builder.append(method);
            builder.append(" ");
            builder.append(url);

            return builder.toString();
        }
    }

    private static ThreadLocal<RpcContext> rpcContext = new ThreadLocal<RpcContext>() {
        @Override
        protected RpcContext initialValue() {
            return new RpcContext();
        }
    };

    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.nianticlabs.pokemongo"))
            return;

        XposedBridge.log("Injecting into PoGo");

        // method is executed in thread context
        findAndHookMethod("com.nianticlabs.nia.network.NiaNet", lpparam.classLoader,
                //               0 object id 1 id       2 url         3 method   4 headers     5 buffer          6 offset   7 size
                "doSyncRequest", long.class, int.class, String.class, int.class, String.class, ByteBuffer.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        RpcContext context = rpcContext.get();

                        // fill in the context, this is threaded so each thread have individual

                        context.threadId = Thread.currentThread().getId();

                        context.objectId = (long) param.args[0];
                        context.requestId = (int) param.args[1];
                        context.url = (String) param.args[2];
                        context.method = Methods[(int) param.args[3]];
                        context.requestHeaders = (String) param.args[4];

                        XposedBridge.log("[request] " + context.shortDump());

                        // read all data from original buffer that is available at the moment

                        ByteBuffer source = (ByteBuffer) param.args[5];
                        ByteBuffer unmodified = ByteBuffer.allocate(source.remaining());

                        if (source.hasArray()) {
                            // make size calculations
                            int offset = source.arrayOffset() + (int) param.args[6];
                            int length = Math.min((int) param.args[7], unmodified.capacity());

                            // copy bytes from original to buffer
                            unmodified.put(source.array(), offset, length);
                        } else {
                            // TODO fix in case of reading from stream
                            // let original pass bytes as it can
                            // if original's capacity has not changed, then all should be fine
                            source.get(unmodified.array(), 0, unmodified.capacity());
                        }

                        unmodified.rewind();

                        // a copy of buffer to be mangled by parsers
                        // it will be used if processing returns true
                        ByteBuffer modified = ByteBuffer.allocate(unmodified.capacity());
                        modified.put(unmodified);
                        modified.rewind();

                        // process data
                        boolean wasModified = DataHandler.processOutboundPackage(context.requestId, modified);
                        ByteBuffer toServer = wasModified ? modified : unmodified;

                        // prepare data for original method
                        toServer.rewind();
                        param.args[5] = toServer;
                        param.args[6] = 0;
                        param.args[7] = toServer.remaining();
                    }
                });

        // method is executed in thread context
        findAndHookMethod("com.nianticlabs.nia.network.NiaNet", lpparam.classLoader,
                "joinHeaders", HttpURLConnection.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        RpcContext context = rpcContext.get();

                        context.responseHeaders = (String) param.getResult();
                        context.responseCode = ((HttpURLConnection) param.args[0]).getResponseCode();
                    }
                });

        // method is executed in thread context
        findAndHookMethod("com.nianticlabs.nia.network.NiaNet", lpparam.classLoader,
                "readDataSteam", HttpURLConnection.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        RpcContext context = rpcContext.get();

                        XposedBridge.log("[response] " + context.shortDump());
                    }
                });
    }
}
