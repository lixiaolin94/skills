package dev.androidbackgroundtest;

/** Entry point started with app_process under the shell user. */
public final class Server {
    private Server() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--build-info".equals(args[0])) {
            System.out.println("{\"name\":\"android-background-test\","
                    + "\"version\":\"" + BuildInfo.VERSION + "\","
                    + "\"protocol\":" + BuildInfo.PROTOCOL + ","
                    + "\"sourceSha256\":\"" + BuildInfo.SOURCE_SHA256 + "\"}");
            return;
        }
        if (args.length == 1 && "coordinator".equals(args[0])) {
            Coordinator.run();
            return;
        }
        System.err.println("usage: Server --build-info | coordinator");
        System.exit(2);
    }
}
