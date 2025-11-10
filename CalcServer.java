import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class CalcServer {
    private final int port;
    private final ExecutorService pool;

    public CalcServer(int port, int threads) {
        this.port = port;
        // fixed size thread pool for handling multiple clients concurrently
        this.pool = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()
        );
    }

    public static void main(String[] args) {
        int port = 9999;
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        try { 
            // start server with desired port and thread count
            new CalcServer(port, threads).start(); 
        } catch (IOException e) { 
            System.out.println("Failed to start server: " + e.getMessage()); 
        }
    }

    public void start() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);                       // allow quick rebinding after restart
            server.bind(new InetSocketAddress("0.0.0.0", port)); // listen on all interfaces
            System.out.println("Server listening on 0.0.0.0:" + port);
            while (true) {
                // accept a new client and hand it to the pool
                Socket s = server.accept();
                s.setSoTimeout(20_000);                         // read timeout to clean up dead clients
                System.out.println("Accepted connection from " + s.getRemoteSocketAddress());
                pool.submit(new ClientHandler(s));
            }
        }
    }

    // semantic response model serialized as text lines
    static final class Response {
        int status; String reason; String id;
        String type;   // ANSWER | ERROR
        String value;  // numeric result when success
        String errCode; // error enum for meaning
        String errMsg;  // short human readable message

        String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append("CALC/1.0 ").append(status).append(" ").append(reason).append("\n");
            if (id != null) sb.append("Id: ").append(id).append("\n");
            if (type != null) sb.append("Type: ").append(type).append("\n");
            if (value != null) sb.append("Value: ").append(value).append("\n");
            if (errCode != null) sb.append("Error-Code: ").append(errCode).append("\n");
            if (errMsg != null) sb.append("Error-Message: ").append(errMsg).append("\n");
            sb.append("\n"); // blank line terminator
            return sb.toString();
        }

        static Response ok(String id, String value) {
            Response r = new Response();
            r.status = 200; r.reason = "OK"; r.id = id; r.type = "ANSWER"; r.value = value; 
            return r;
        }
        static Response bad(String id, int status, String reason, String code, String msg) {
            Response r = new Response();
            r.status = status; r.reason = reason; r.id = id; r.type = "ERROR"; r.errCode = code; r.errMsg = msg; 
            return r;
        }
    }
}

final class ClientHandler implements Runnable {
    private final Socket socket;
    ClientHandler(Socket s) { this.socket = s; }

    @Override public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            // handle multiple lines on a single connection until client sends bye or closes
            while ((line = in.readLine()) != null) {
                if (line.equalsIgnoreCase("bye")) break;

                // build semantic response for each input line
                CalcServer.Response resp = handle(line);
                out.write(resp.serialize());
                out.flush();
            }
        } catch (IOException ignored) {
            // connection closed or timeout
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // compute and return a semantic response
    private CalcServer.Response handle(String exp) {
        String id = UUID.randomUUID().toString(); // per request correlation id

        ParseResult pr = parse(exp);
        if (!pr.ok) return CalcServer.Response.bad(id, 400, "BadRequest", pr.errCode, pr.errMsg);

        try {
            double a = pr.a, b = pr.b;
            double v;
            switch (pr.op) {
                case ADD: v = a + b; break;
                case SUB: v = a - b; break;
                case MUL: v = a * b; break;
                case DIV:
                    if (b == 0.0) return CalcServer.Response.bad(id, 422, "InvalidOperation", "DIV_BY_ZERO", "divided by zero");
                    v = a / b; break;
                default:
                    return CalcServer.Response.bad(id, 404, "UnknownOperation", "UNKNOWN_OP", "unsupported operation");
            }
            // render integer like 30 instead of 30.0
            String value = (Math.floor(v) == v) ? Long.toString((long)v) : Double.toString(v);
            return CalcServer.Response.ok(id, value);
        } catch (Exception e) {
            return CalcServer.Response.bad(id, 500, "ServerError", "SERVER_ERROR", "server error");
        }
    }

    // parse either prefix form `OP A B` or infix form `A op B`
    private ParseResult parse(String s) {
        StringTokenizer st = new StringTokenizer(s, " ");
        int n = st.countTokens();
        if (n < 3) return ParseResult.err("BAD_FORMAT", "bad format");
        if (n > 3) return ParseResult.err("TOO_MANY_ARGS", "too many arguments");

        String t1 = st.nextToken(), t2 = st.nextToken(), t3 = st.nextToken();

        // prefix form: OP A B
        Op op = Op.fromToken(t1);
        if (op != null) {
            Double a = num(t2), b = num(t3);
            if (a == null || b == null) return ParseResult.err("INVALID_NUMBER", "invalid number");
            return ParseResult.ok(op, a, b);
        }
        // infix form: A op B
        Double a = num(t1), b = num(t3);
        if (a == null || b == null) return ParseResult.err("INVALID_NUMBER", "invalid number");
        op = Op.fromToken(t2);
        if (op == null) return ParseResult.err("UNKNOWN_OP", "unsupported operation");
        return ParseResult.ok(op, a, b);
    }

    // safe number parse returning null on failure
    private Double num(String s) { 
        try { return Double.parseDouble(s); } 
        catch (Exception e) { return null; } 
    }

    // supported operations mapping from tokens
    enum Op { ADD, SUB, MUL, DIV;
        static Op fromToken(String t) {
            t = t.toUpperCase(Locale.ROOT);
            switch (t) {
                case "ADD": case "+": return ADD;
                case "SUB": case "-": return SUB;
                case "MUL": case "*": return MUL;
                case "DIV": case "/": return DIV;
                default: return null;
            }
        }
    }

    // parse result carrying either op and operands or error info
    static final class ParseResult {
        final boolean ok; 
        final Op op; 
        final double a, b; 
        final String errCode, errMsg;

        private ParseResult(boolean ok, Op op, double a, double b, String ec, String em) {
            this.ok = ok; this.op = op; this.a = a; this.b = b; this.errCode = ec; this.errMsg = em;
        }
        static ParseResult ok(Op op, double a, double b) { return new ParseResult(true, op, a, b, null, null); }
        static ParseResult err(String ec, String em) { return new ParseResult(false, null, 0, 0, ec, em); }
    }
}
