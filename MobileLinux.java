import java.io.*;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Mobile Linux — мини-терминал: creato (создать файл и редактировать), python, exit.
 * Запуск (Java 11+):  java MobileLinux.java
 */
public class MobileLinux {
    static final Reader in = new InputStreamReader(System.in, StandardCharsets.UTF_8);
    static final PrintStream out =
            new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
    static final Object LOCK = new Object();

    // коды специальных клавиш (за пределами Unicode)
    static final int K_UP = 0x110001, K_DOWN = 0x110002, K_LEFT = 0x110003, K_RIGHT = 0x110004,
            K_HOME = 0x110005, K_END = 0x110006, K_PGUP = 0x110007, K_PGDN = 0x110008,
            K_DEL = 0x110009, K_MAX = 0x110000;

    static int rows = 24, cols = 80;
    static volatile Runnable onResize = () -> {};

    static final String PROMPT = "\033[32m~\033[0m $ ";
    static final StringBuilder buf = new StringBuilder();
    static int pos, histIdx, curRow;
    static final List<String> history = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        stty("-icanon -echo -isig -ixon min 1");
        out.print("\033[?1000l\033[?1006l");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.print("\033[?25h");
            out.flush();
            stty("sane");
        }));
        installResize();
        updateSize();
        onResize = MobileLinux::updateSize;
        banner();

        while (true) {
            String line = readShellLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            history.add(line);
            String[] t = line.split("\\s+", 2);
            if (t[0].equals("exit")) break;
            else if (t[0].equals("creato")) {
                if (t.length < 2) out.println("creato: укажи имя файла, например: creato main.py");
                else creato(t[1].trim());
            } else if (t[0].equals("start")) {
                startCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("download")) {
                downloadCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("python") || t[0].equals("python3")) {
                runPython(t.length > 1 ? t[1] : "");
            } else out.println(t[0] + ": команда не найдена (пока есть: creato, download, start, python, exit)");
        }
    }

    static void banner() {
        out.println("Welcome to Mobile Linux");
        out.println();
        out.println("Docs:       https://github.com/coreneuralsystem-ai/MobileLinux");
        out.println();
        out.println("Working with files:");
        out.println(" - Create:  creato <file>");
        out.println(" - Install: download pkt python");
        out.println(" - Start:   start python <file.py>");
        out.println(" - Run:     python <file.py>");
        out.println(" - Exit:    exit");
        out.println();
        out.println("Report issues at https://github.com/coreneuralsystem-ai/MobileLinux/issues");
        out.println();
    }

    // ---------- пакеты ----------
    static final String PY_VER = "2.7.4";
    static final String PY_URL =
            "https://repo1.maven.org/maven2/org/python/jython-standalone/2.7.4/jython-standalone-2.7.4.jar";

    static void pause(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    static String mb(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }

    static Path[] pkgDirs() {
        return new Path[]{
            Paths.get(System.getProperty("user.home"), ".mobilelinux", "pkg"),
            Paths.get(System.getProperty("java.io.tmpdir"), "mobilelinux-pkg")};
    }

    static Path findPyJar() {
        for (Path d : pkgDirs()) {
            Path j = d.resolve("jython-standalone-" + PY_VER + ".jar");
            if (Files.isRegularFile(j)) return j;
        }
        return null;
    }

    /** Папка для пакетов: домашняя, а если туда писать нельзя — временная. */
    static Path writablePkgDir() {
        for (Path d : pkgDirs()) {
            try {
                Files.createDirectories(d);
                if (Files.isWritable(d)) return d;
            } catch (IOException ignored) {}
        }
        return null;
    }

    static long headSize(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            long len = c.getContentLengthLong();
            c.disconnect();
            return code == 200 ? len : -1;
        } catch (Exception e) { return -1; }
    }

    static void step(String text, int ms) {
        out.print(text + "...");
        out.flush();
        pause(ms);
        out.println(" Done");
    }

    /** download pkt python */
    static void downloadCmd(String args) throws IOException {
        String[] a = args.trim().split("\\s+");
        if (a.length < 2 || !a[0].equals("pkt")) { out.println("Использование: download pkt python"); return; }
        if (!a[1].equals("python")) { out.println("E: пакет '" + a[1] + "' не найден. Пока есть только: python"); return; }
        installPython();
    }

    /** Ищет файл jython-standalone*.jar, который пользователь уже скачал сам. */
    static Path findLocalJar() {
        String home = System.getProperty("user.home");
        String[] ds = {System.getProperty("user.dir"), home, "/sdcard/Download",
                "/storage/emulated/0/Download", "/sdcard", home + "/storage/downloads"};
        for (String d : ds) {
            if (d == null) continue;
            Path dir = Paths.get(d);
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> st = Files.newDirectoryStream(dir, "jython-standalone*.jar")) {
                for (Path x : st) if (Files.isRegularFile(x) && Files.size(x) > 5_000_000) return x;
            } catch (IOException ignored) {}
        }
        return null;
    }

    static void installPython() throws IOException {
        if (findPyJar() != null) {
            out.println("python is already the newest version (" + PY_VER + ").");
            return;
        }
        Path local = findLocalJar();
        // --- начало: как в Termux ---
        out.println("Checking availability of repository:");
        long size;
        if (local != null) {
            out.print("[*] " + local.getParent() + ": ");
            out.flush();
            size = Files.size(local);
            pause(300);
            out.println("ok");
        } else {
            out.print("[*] repo1.maven.org: ");
            out.flush();
            size = headSize(PY_URL);
            if (size <= 0) {
                out.println("failed");
                out.println("E: нет связи с репозиторием, и рядом нет файла jython-standalone*.jar.");
                out.println("   Положи файл в папку " + System.getProperty("user.dir") + " и повтори команду.");
                return;
            }
            pause(300);
            out.println("ok");
        }
        step("Reading package lists", 350);
        step("Building dependency tree", 350);
        step("Reading state information", 300);
        out.println("The following NEW packages will be installed:");
        out.println("  python");
        out.println("0 upgraded, 1 newly installed, 0 to remove and 0 not upgraded.");
        out.println("Need to get " + mb(size) + " of archives.");
        out.println("After this operation, " + mb(size) + " of additional disk space will be used.");
        out.print("Do you want to continue? [Y/n] ");
        out.flush();
        while (true) {
            int k = readKey();
            if (k == -1) return;
            if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { out.println("Y"); break; }
            if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') { out.println("N"); out.println("Abort."); return; }
        }

        Path dir = writablePkgDir();
        if (dir == null) { out.println("E: некуда сохранять пакет (нет доступной для записи папки)."); return; }
        Path jar = dir.resolve("jython-standalone-" + PY_VER + ".jar");
        Path part = dir.resolve(jar.getFileName() + ".part");
        String src = local != null ? "file:" + local : "https://repo1.maven.org/maven2";
        out.println("Get:1 " + src + " stable/main all python all " + PY_VER + " [" + mb(size) + "]");
        long t0 = System.currentTimeMillis();
        try {
            if (local != null) {
                try (InputStream is = Files.newInputStream(local)) { transfer(is, part, size); }
            } else downloadFile(PY_URL, part, size);
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            out.println();
            out.println("E: не удалось установить: " + e.getMessage());
            return;
        }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        out.println(String.format(Locale.US, "Fetched %s in %ds (%.1f MB/s)", mb(size), ms / 1000,
                size / 1048576.0 / (ms / 1000.0)));

        // --- конец: как в Termux ---
        out.println("Selecting previously unselected package python.");
        for (int pct = 0; pct <= 100; pct += 20) {
            out.print("\r(Reading database ... " + pct + "%");
            out.flush();
            pause(150);
        }
        out.println();
        out.println("Preparing to unpack python_" + PY_VER + "_all.jar ...");
        pause(300);
        out.println("Unpacking python (" + PY_VER + ") ...");
        pause(500);
        out.println("Setting up python (" + PY_VER + ") ...");
        pause(500);
        out.println();
        out.println("Готово. Пакет python установлен: " + jar);
        out.println("Это Jython (Python 2.7 на Java): print(\"текст\") работает, f-строк нет.");
        out.println("Запуск:  start python файл.py");
    }

    static void progress(long done, long total, long ms) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        int barW = cols < 60 ? 8 : Math.max(8, Math.min(30, cols - 45));
        int filled = barW * pct / 100;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barW; i++) bar.append(i < filled ? '=' : (i == filled ? '>' : ' '));
        bar.append(']');
        double speed = ms > 0 ? done / 1048576.0 / (ms / 1000.0) : 0;
        out.print(String.format(Locale.US, "\r%3d%% %s %.1f/%.1f MB  %.1f MB/s ", pct, bar,
                done / 1048576.0, total / 1048576.0, speed));
        out.flush();
    }

    /** Копирует поток в файл, рисуя полосу прогресса. */
    static void transfer(InputStream is, Path dest, long total) throws IOException {
        long start = System.currentTimeMillis(), last = 0, done = 0;
        try (OutputStream os = Files.newOutputStream(dest)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) {
                os.write(b, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 120) { last = now; progress(done, total, now - start); }
            }
        }
        progress(done, total, System.currentTimeMillis() - start);
        out.println();
        if (total > 0 && done != total) throw new IOException("файл получен не полностью");
    }

    static void downloadFile(String url, Path dest, long total) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream is = c.getInputStream()) {
            transfer(is, dest, total);
        } finally {
            c.disconnect();
        }
    }

    // ---------- start python <файл> ----------
    static Path findFile(String name) {
        try {
            Path p = Paths.get(name);
            if (p.isAbsolute() && Files.isRegularFile(p)) return p;
            Path[] roots = {Paths.get(System.getProperty("user.dir")), Paths.get(System.getProperty("user.home"))};
            for (Path r : roots) {
                Path c = r.resolve(name).normalize();
                if (Files.isRegularFile(c)) return c.toAbsolutePath();
            }
            String base = p.getFileName().toString();
            for (Path r : roots) {
                try (java.util.stream.Stream<Path> st = Files.walk(r, 4)) {
                    Optional<Path> m = st.filter(x -> x.getFileName() != null
                            && x.getFileName().toString().equals(base) && Files.isRegularFile(x)).findFirst();
                    if (m.isPresent()) return m.get().toAbsolutePath();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    static void startCmd(String args) {
        String[] a = args.trim().split("\\s+", 2);
        if (a.length < 2 || a[0].isEmpty() || a[1].trim().isEmpty()) {
            out.println("Использование: start python <файл.py>   (например: start python dias.py)");
            return;
        }
        String lang = a[0].toLowerCase();
        if (!lang.equals("python") && !lang.equals("python3")) {
            out.println("start: язык '" + a[0] + "' пока не поддерживается. Есть: python");
            return;
        }
        startPython(a[1].trim());
    }

    static void startPython(String file) {
        out.println("Starting python...");
        pause(300);
        out.print("Checking package python... ");
        out.flush();
        pause(300);
        Path jar = findPyJar();
        if (jar == null) {
            out.println("not found");
            out.println("E: пакет не установлен. Введи: download pkt python");
            return;
        }
        out.println("ok (" + jar.getFileName() + ")");
        out.print("Searching for " + file + "... ");
        out.flush();
        pause(300);
        Path f = findFile(file);
        if (f == null) {
            out.println("not found");
            out.println("E: файл не найден (искал в текущей папке, домашней и подпапках).");
            return;
        }
        out.println("found");
        out.println("  " + f);

        try {   // Jython 2.7 требует строку-кодировку, если в файле есть русские буквы
            List<String> ls = Files.readAllLines(f, StandardCharsets.UTF_8);
            boolean nonAscii = false, coding = false;
            for (int i = 0; i < ls.size(); i++) {
                for (char ch : ls.get(i).toCharArray()) if (ch > 127) nonAscii = true;
                if (i < 2 && ls.get(i).contains("coding")) coding = true;
            }
            if (nonAscii && !coding) {
                out.println("W: в файле есть не-латинские буквы. Добавь первой строкой:");
                out.println("   # -*- coding: utf-8 -*-");
            }
        } catch (IOException ignored) {}

        out.println("Running python " + f.getFileName() + ":");
        out.println("----------------------------------------");
        int code = -1;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        if (!Files.isExecutable(Paths.get(javaBin))) javaBin = "java";
        List<String> cmd = new ArrayList<>(Arrays.asList(javaBin,
                "-Dfile.encoding=UTF-8",
                "-Dpython.cachedir=" + jar.getParent().resolve("cachedir"),
                "-jar", jar.toString(), f.toString()));
        stty("icanon echo isig ixon");
        try {
            code = new ProcessBuilder(cmd).directory(f.getParent().toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            out.println("E: не удалось запустить Java: " + e.getMessage());
        } catch (InterruptedException ignored) {
        } finally {
            stty("-icanon -echo -isig -ixon min 1");
        }
        out.println("----------------------------------------");
        out.println("[Process completed (code " + code + ")]");
    }

    /** python: сначала установленный пакет (Jython), потом системный Python. */
    static void runPython(String args) {
        List<String> a = args.trim().isEmpty() ? new ArrayList<>() : Arrays.asList(args.trim().split("\\s+"));
        Path jar = findPyJar();
        if (jar != null) {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            if (!Files.isExecutable(Paths.get(javaBin))) javaBin = "java";
            List<String> cmd = new ArrayList<>(Arrays.asList(javaBin, "-jar", jar.toString()));
            cmd.addAll(a);
            stty("icanon echo isig ixon");
            try {
                new ProcessBuilder(cmd).inheritIO().start().waitFor();
            } catch (IOException e) {
                out.println("python: не удалось запустить Java: " + e.getMessage());
            } catch (InterruptedException ignored) {
            } finally {
                stty("-icanon -echo -isig -ixon min 1");
            }
            return;
        }
        for (String exe : new String[]{"python3", "python"}) {
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.addAll(a);
            stty("icanon echo isig ixon");
            try {
                new ProcessBuilder(cmd).inheritIO().start().waitFor();
                stty("-icanon -echo -isig -ixon min 1");
                return;
            } catch (IOException e) {
                stty("-icanon -echo -isig -ixon min 1");
            } catch (InterruptedException e) {
                stty("-icanon -echo -isig -ixon min 1");
                return;
            }
        }
        out.println("python: не найден. Установи пакет командой: download pkt python");
    }

    // ---------- строка команд ----------
    static void refreshLine() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            if (curRow > 0) sb.append("\033[").append(curRow).append('A');
            sb.append("\r\033[J").append(PROMPT).append(buf);
            int endAbs = 4 + buf.length();                       // "~ $ " = 4 символа
            if (endAbs % cols == 0) sb.append(" \r");            // принудительный перенос
            int endRow = endAbs / cols;
            int curAbs = 4 + pos;
            int row = curAbs / cols, col = curAbs % cols;
            if (row < endRow) sb.append("\033[").append(endRow - row).append('A');
            else if (row > endRow) sb.append("\033[").append(row - endRow).append('B');
            sb.append("\033[").append(col + 1).append('G');
            curRow = row;
            out.print(sb);
            out.flush();
        }
    }

    static String readShellLine() throws IOException {
        buf.setLength(0);
        pos = 0;
        histIdx = history.size();
        curRow = 0;
        refreshLine();
        while (true) {
            int k = readKey();
            if (k == -1) return null;
            if (k == 13 || k == 10) {
                int endRow = (4 + buf.length()) / cols;
                if (endRow > curRow) out.print("\033[" + (endRow - curRow) + "B");
                out.print("\r\n");
                out.flush();
                return buf.toString();
            }
            else if (k == 3) { out.print("^C\r\n"); out.flush(); return ""; }
            else if (k == 4) { if (buf.length() == 0) { out.print("\r\n"); return null; } }
            else if (k == 12) { out.print("\033[2J\033[H"); curRow = 0; refreshLine(); }
            else if (k == 127 || k == 8) { if (pos > 0) { buf.deleteCharAt(pos - 1); pos--; refreshLine(); } }
            else if (k == K_DEL) { if (pos < buf.length()) { buf.deleteCharAt(pos); refreshLine(); } }
            else if (k == K_LEFT) { if (pos > 0) { pos--; refreshLine(); } }
            else if (k == K_RIGHT) { if (pos < buf.length()) { pos++; refreshLine(); } }
            else if (k == K_HOME) { pos = 0; refreshLine(); }
            else if (k == K_END) { pos = buf.length(); refreshLine(); }
            else if (k == K_UP) {
                if (histIdx > 0) { histIdx--; buf.setLength(0); buf.append(history.get(histIdx)); pos = buf.length(); refreshLine(); }
            } else if (k == K_DOWN) {
                if (histIdx < history.size()) {
                    histIdx++;
                    buf.setLength(0);
                    if (histIdx < history.size()) buf.append(history.get(histIdx));
                    pos = buf.length();
                    refreshLine();
                }
            } else if (k >= 32 && k < K_MAX) { buf.insert(pos, (char) k); pos++; refreshLine(); }
        }
    }

    // ---------- терминал ----------
    static void stty(String a) {
        try { new ProcessBuilder("sh", "-c", "stty " + a + " < /dev/tty").inheritIO().start().waitFor(); }
        catch (Exception ignored) {}
    }

    static void updateSize() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] x = s.split("\\s+");
            rows = Integer.parseInt(x[0]);
            cols = Integer.parseInt(x[1]);
        } catch (Exception e) { rows = 24; cols = 80; }
        if (rows < 16) rows = 16;
        if (cols < 24) cols = 24;
    }

    static void installResize() {
        try {
            Class<?> sig = Class.forName("sun.misc.Signal");
            Class<?> hnd = Class.forName("sun.misc.SignalHandler");
            Object s = sig.getConstructor(String.class).newInstance("WINCH");
            Object h = Proxy.newProxyInstance(hnd.getClassLoader(), new Class<?>[]{hnd}, (p, m, a) -> {
                if (m.getName().equals("handle")) { try { onResize.run(); } catch (Throwable ignored) {} }
                return null;
            });
            sig.getMethod("handle", sig, hnd).invoke(null, s, h);
        } catch (Throwable ignored) {}
    }

    static String fit(String s, int w) {
        if (w <= 0) return "";
        if (s.length() > w) return s.substring(0, w);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    static String center(String s, int w) {
        return fit(" ".repeat(Math.max(0, (w - s.length()) / 2)) + s, w);
    }

    // ---------- ввод ----------
    static int readKey() throws IOException {
        while (true) {
            int c = in.read();
            if (c == -1) return -1;
            if (c != 27) return c;
            if (!in.ready()) return 27;
            int d = in.read();
            if (d != '[' && d != 'O') continue;
            StringBuilder pr = new StringBuilder();
            int e;
            while ((e = in.read()) != -1) {
                if (e >= 0x40 && e <= 0x7E) break;
                pr.append((char) e);
            }
            int k = seqKey(pr.toString(), e);
            if (k != 0) return k;
        }
    }

    static int seqKey(String ps, int e) {
        String num = ps.split(";")[0];
        switch (e) {
            case 'A': return K_UP;
            case 'B': return K_DOWN;
            case 'C': return K_RIGHT;
            case 'D': return K_LEFT;
            case 'H': return K_HOME;
            case 'F': return K_END;
            case '~':
                switch (num) {
                    case "1": case "7": return K_HOME;
                    case "4": case "8": return K_END;
                    case "3": return K_DEL;
                    case "5": return K_PGUP;
                    case "6": return K_PGDN;
                }
        }
        return 0;
    }

    // ---------- creato ----------
    static List<StringBuilder> L;
    static int cx, cy, top, left;
    static boolean modified, cmdMode;
    static String status = "", lang = "Текст", curName = "";
    static StringBuilder clip;
    static Path curPath;

    static String detectLang(String name) {
        String n = name.toLowerCase();
        int i = n.lastIndexOf('.');
        String e = i >= 0 ? n.substring(i + 1) : "";
        switch (e) {
            case "py": return "Python";
            case "java": return "Java";
            case "js": return "JavaScript";
            case "ts": return "TypeScript";
            case "c": return "C";
            case "cpp": case "cc": case "cxx": return "C++";
            case "h": case "hpp": return "C/C++ header";
            case "sh": case "bash": return "Shell";
            case "go": return "Go";
            case "rs": return "Rust";
            case "php": return "PHP";
            case "lua": return "Lua";
            case "rb": return "Ruby";
            case "pl": return "Perl";
            case "kt": return "Kotlin";
            case "html": case "htm": return "HTML";
            case "css": return "CSS";
            case "json": return "JSON";
            case "md": return "Markdown";
            case "sql": return "SQL";
            case "xml": return "XML";
            case "yml": case "yaml": return "YAML";
            default: return "Текст";
        }
    }

    static void creato(String name) throws IOException {
        Path p = Paths.get(name).toAbsolutePath();
        if (Files.isDirectory(p)) { out.println("creato: " + name + ": это папка"); return; }
        boolean existed = Files.exists(p);
        if (!existed) {
            try { Files.createFile(p); }
            catch (IOException e) { out.println("creato: " + name + ": " + e.getMessage()); return; }
        }
        L = new ArrayList<>();
        if (existed) for (String s : Files.readAllLines(p)) L.add(new StringBuilder(s));
        if (L.isEmpty()) L.add(new StringBuilder());
        cx = 0; cy = 0; top = 0; left = 0; modified = false; clip = null; cmdMode = false;
        curPath = p;
        curName = name;
        lang = detectLang(name);
        status = existed ? "Прочитано строк: " + L.size() : "Создан новый файл";

        updateSize();
        out.print("\033[?1049h\033[2J");
        onResize = () -> { updateSize(); synchronized (LOCK) { out.print("\033[2J"); draw(); } };
        try {
            while (true) {
                draw();
                int c = readKey();
                if (c == -1) break;
                status = "";
                if (c == 24) { if (tryExit()) break; }                 // Ctrl+X
                else if (cmdMode) { if (command(c)) break; }
                else if (c == 9 || c == 27 || c == '`') { cmdMode = true; dotPending = false; status = ">> Режим команд включён"; }
                else if (c == ',' && cx > 0 && L.get(cy).charAt(cx - 1) == ',') {
                    L.get(cy).deleteCharAt(cx - 1);                        // две запятые ,, — режим команд
                    cx--;
                    cmdMode = true;
                    dotPending = false;
                    status = ">> Режим команд включён";
                }
                else handleKey(c);
            }
        } finally {
            onResize = MobileLinux::updateSize;
            out.print("\033[?25h\033[?1049l");
            out.flush();
        }
    }

    static boolean dotPending;

    /** Режим команд: одна клавиша — одно действие. Выход из режима — две точки. true = выйти из creato. */
    static boolean command(int c) throws IOException {
        int k = c < K_MAX ? Character.toLowerCase(c) : c;
        if (k == '.') {
            if (dotPending) { dotPending = false; cmdMode = false; status = "<< Режим текста. Можно писать."; }
            else { dotPending = true; status = "Нажми точку ещё раз, чтобы выйти из команд"; }
            return false;
        }
        dotPending = false;
        if (k == 'q' || k == 'й') save();                                     // Q — сохранить
        else if (k == 'x' || k == 'ч') return tryExit();                      // X — выйти
        else if (k == 'n' || k == 'т') newFile();                             // N — новый файл
        else if (k == 9 || k == 27) { cmdMode = false; status = "<< Режим текста. Можно писать."; }
        else if (k >= K_UP && k <= K_PGDN) handleKey(c);                      // стрелки работают и тут
        else status = "Режим команд: Q сохранить, X выйти, N новый файл. Две точки .. — писать текст";
        return false;
    }

    /** Выход: если есть несохранённые правки — спрашивает. true = выйти. */
    static boolean tryExit() throws IOException {
        if (!modified) return true;
        status = "Сохранить изменения? Нажми Y — да, N — нет, другая клавиша — отмена";
        draw();
        int k = readKey();
        if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { save(); return true; }
        if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') return true;
        status = "Отменено";
        return false;
    }

    /** Новый файл прямо из creato: спрашивает имя, создаёт и открывает. */
    static void newFile() {
        try {
            String name = promptName();
            if (name == null || name.isEmpty()) { status = "Отменено"; return; }
            if (modified) {
                status = "Сохранить текущий файл? Y — да, N — нет, другая клавиша — отмена";
                draw();
                int k = readKey();
                if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') save();
                else if (k != 'n' && k != 'N' && k != 'т' && k != 'Т') { status = "Отменено"; return; }
            }
            Path np = Paths.get(name).toAbsolutePath();
            if (Files.isDirectory(np)) { status = "Это папка"; return; }
            boolean existed = Files.exists(np);
            if (!existed) Files.createFile(np);
            L = new ArrayList<>();
            if (existed) for (String line : Files.readAllLines(np)) L.add(new StringBuilder(line));
            if (L.isEmpty()) L.add(new StringBuilder());
            curPath = np;
            curName = name;
            lang = detectLang(name);
            cx = 0; cy = 0; top = 0; left = 0; modified = false;
            status = existed ? "Открыт существующий: " + name : "Создан файл: " + name;
        } catch (IOException e) {
            status = "Ошибка: " + e.getMessage();
        }
    }

    static String promptName() throws IOException {
        StringBuilder nm = new StringBuilder();
        while (true) {
            status = "Имя файла: " + nm + "_  (Enter — создать, ESC — отмена)";
            draw();
            int k = readKey();
            if (k == 13 || k == 10) return nm.toString().trim();
            if (k == 27) return null;
            if (k == 127 || k == 8) { if (nm.length() > 0) nm.deleteCharAt(nm.length() - 1); }
            else if (k >= 32 && k < K_MAX) nm.append((char) k);
        }
    }

    static int panelH() { return cmdMode ? 9 : 4; }
    static int bodyRows() { return rows - 1 - panelH(); }

    static void handleKey(int c) throws IOException {
        int bodyRows = bodyRows();
        switch (c) {
            case K_UP: moveV(-1); return;
            case K_DOWN: moveV(1); return;
            case K_LEFT: moveH(-1); return;
            case K_RIGHT: moveH(1); return;
            case K_HOME: cx = 0; return;
            case K_END: cx = L.get(cy).length(); return;
            case K_PGUP: moveV(-bodyRows); return;
            case K_PGDN: moveV(bodyRows); return;
            case K_DEL: delete(); return;
            case 13: case 10: enter(); return;
            case 127: case 8: backspace(); return;
            case 15: save(); return;                        // Ctrl+O
            case 1: cx = 0; return;                         // Ctrl+A
            case 5: cx = L.get(cy).length(); return;        // Ctrl+E
            case 11:                                        // Ctrl+K — вырезать строку
                clip = L.remove(cy);
                if (L.isEmpty()) L.add(new StringBuilder());
                if (cy >= L.size()) cy = L.size() - 1;
                cx = Math.min(cx, L.get(cy).length());
                modified = true;
                return;
            case 21:                                        // Ctrl+U — вставить строку
                if (clip != null) { L.add(cy, new StringBuilder(clip)); cy++; modified = true; }
                return;
            default:
                if (c >= 32 && c < K_MAX) insert(String.valueOf((char) c));
        }
    }

    static void moveV(int n) {
        cy = Math.max(0, Math.min(L.size() - 1, cy + n));
        cx = Math.min(cx, L.get(cy).length());
    }

    static void moveH(int n) {
        if (n < 0) {
            if (cx > 0) cx--;
            else if (cy > 0) { cy--; cx = L.get(cy).length(); }
        } else {
            if (cx < L.get(cy).length()) cx++;
            else if (cy < L.size() - 1) { cy++; cx = 0; }
        }
    }

    static void insert(String s) { L.get(cy).insert(cx, s); cx += s.length(); modified = true; }

    static void enter() {
        StringBuilder line = L.get(cy);
        String before = line.substring(0, cx);
        StringBuilder indent = new StringBuilder();
        for (char ch : before.toCharArray()) { if (ch == ' ' || ch == '\t') indent.append(ch); else break; }
        if (lang.equals("Python") && before.trim().endsWith(":")) indent.append("    ");
        String rest = line.substring(cx);
        line.setLength(cx);
        L.add(cy + 1, new StringBuilder(indent + rest));
        cy++; cx = indent.length(); modified = true;
    }

    static void backspace() {
        if (cx > 0) { L.get(cy).deleteCharAt(cx - 1); cx--; modified = true; }
        else if (cy > 0) {
            int len = L.get(cy - 1).length();
            L.get(cy - 1).append(L.get(cy));
            L.remove(cy); cy--; cx = len; modified = true;
        }
    }

    static void delete() {
        if (cx < L.get(cy).length()) { L.get(cy).deleteCharAt(cx); modified = true; }
        else if (cy < L.size() - 1) { L.get(cy).append(L.get(cy + 1)); L.remove(cy + 1); modified = true; }
    }

    static void save() throws IOException {
        List<String> s = new ArrayList<>();
        for (StringBuilder b : L) s.add(b.toString());
        Files.write(curPath, s);
        modified = false;
        status = "Сохранено. Строк: " + s.size();
    }

    static void draw() {
        synchronized (LOCK) {
            int bodyRows = bodyRows();
            if (cy < top) top = cy;
            if (cy >= top + bodyRows) top = cy - bodyRows + 1;
            if (cx < left) left = cx;
            if (cx >= left + cols - 1) left = cx - cols + 2;

            StringBuilder sb = new StringBuilder("\033[?25l\033[H");
            String hl = "  Mobile Linux creato   " + curName + (modified ? "  [изменён]" : "")
                    + (cmdMode ? "  >> РЕЖИМ КОМАНД" : "");
            String hr = lang + "  ";
            int gap = Math.max(1, cols - hl.length() - hr.length());
            sb.append("\033[7m").append(fit(hl + " ".repeat(gap) + hr, cols - 1)).append("\033[0m");

            for (int i = 0; i < bodyRows; i++) {
                sb.append("\033[").append(i + 2).append(";1H\033[K");
                int idx = top + i;
                if (idx < L.size()) {
                    String s = L.get(idx).toString();
                    if (left < s.length()) sb.append(s, left, Math.min(s.length(), left + cols - 1));
                }
            }

            int w = cols - 1;
            int r = rows - panelH() + 1;                 // первая строка нижней панели
            String st = status.isEmpty()
                    ? (cmdMode ? ">> Режим команд. Q, X, N. Две точки .. — назад к тексту." : "Пишешь текст. Две запятые ,, — команды.")
                    : status;
            sb.append("\033[").append(r).append(";1H\033[K\033[1m").append(fit(st, w).trim()).append("\033[0m");
            r++;

            if (!cmdMode) {
                // одна большая подсказка в 3 строки
                String color = "\033[48;5;24;97;1m";
                String text = "Две запятые ,, = команды (Q сохранить, X выйти, N новый)";
                for (int l = 0; l < 3; l++) {
                    sb.append("\033[").append(r++).append(";1H\033[K").append(color)
                      .append(l == 1 ? center(text, w) : " ".repeat(w)).append("\033[0m");
                }
            } else {
                String[] labels = {"Q   СОХРАНИТЬ", "X   ВЫЙТИ", "N   НОВЫЙ ФАЙЛ", "ДВЕ ТОЧКИ ..   НАЗАД К ТЕКСТУ"};
                String[] colors = {"\033[48;5;28;97;1m", "\033[48;5;124;97;1m",
                                   "\033[48;5;25;97;1m", "\033[48;5;240;97;1m"};
                for (int i = 0; i < 4; i++) {
                    for (int l = 0; l < 2; l++) {
                        sb.append("\033[").append(r++).append(";1H\033[K").append(colors[i])
                          .append(l == 0 ? center(labels[i], w) : " ".repeat(w)).append("\033[0m");
                    }
                }
            }

            sb.append("\033[").append(cy - top + 2).append(';').append(cx - left + 1).append("H\033[?25h");
            out.print(sb);
            out.flush();
        }
    }
}
