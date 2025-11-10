import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CalcClient {
    public static void main(String[] args) {
        // default target server
        String host = "127.0.0.1";
        int port = 9999;

        // optional config file to override host and port
        File cfg = new File("server_info.dat");
        if (cfg.exists()) {
            Properties p = new Properties();
            try (FileInputStream fis = new FileInputStream(cfg)) {
                p.load(fis);
                host = p.getProperty("host", host).trim();
                port = Integer.parseInt(p.getProperty("port", String.valueOf(port)).trim());
            } catch (Exception ignored) {
                // ignore malformed config and keep defaults
            }
        }

        // open TCP connection and prepare UTF-8 I/O
        try (Socket s = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
             Scanner sc = new Scanner(System.in)) {

            System.out.println("Connected to " + host + ":" + port);

            // interactive loop until user types bye or server closes
            while (true) {
                System.out.print("Expression (ADD 10 20 or 24 + 42), 'bye' to exit >> ");
                String msg = sc.nextLine();

                // send one line request
                out.write(msg + "\n");
                out.flush();

                // client initiated close
                if (msg.equalsIgnoreCase("bye")) break;

                // read status line of semantic response
                String start = in.readLine();
                if (start == null) { 
                    System.out.println("Server closed"); 
                    break; 
                }

                // read header fields until blank line
                Map<String,String> h = new HashMap<>();
                String line;
                while ((line = in.readLine()) != null && !line.trim().isEmpty()) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        String key = line.substring(0, idx).trim();
                        String val = line.substring(idx + 1).trim();
                        h.put(key, val);
                    }
                }

                // parse status code and reason from the start line
                int status = 0; 
                String reason = "";
                try {
                    String[] sp = start.split("\\s+", 3);
                    status = Integer.parseInt(sp[1]);
                    reason = sp.length >= 3 ? sp[2] : "";
                } catch (Exception ignored) {
                    // keep defaults if parsing fails
                }

                // UI rule
                // success → print Answer with Value
                // error   → print Error message with Error-Message or reason
                if (status == 200 && "ANSWER".equalsIgnoreCase(h.get("Type"))) {
                    System.out.println("Answer: " + h.get("Value"));
                } else {
                    String em = h.getOrDefault("Error-Message", reason);
                    System.out.println("Error message: " + em);
                }
            }
        } catch (IOException e) {
            // connection setup or I/O failure
            System.out.println("Connection error: " + e.getMessage());
        }
    }
}
