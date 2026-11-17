import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Xat TCP multiusuari amb finestra (Swing).
 *
 * - El HOST fa de servidor i també xateja. Té un panell d'administració:
 *   canviar rol, canviar color, silenciar, expulsar i netejar el xat.
 * - Els altres CONNECTEN a la IP del host. Tots executen aquest mateix programa.
 *
 * Protocol (una línia per missatge, UTF-8):
 *   Client -> Servidor:  HELLO|nom   MSG|text
 *   Servidor -> Client:  WELCOME|nom   MSG|nom|rol|#color|hora|text   SYS|text
 *                        USERS|nom,rol,#color,0/1;...   CLEAR   KICKED|motiu
 */
public class XatTCP extends JFrame {

    static final int PORT_PER_DEFECTE = 8080;
    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    static final String COLOR_HOST = "#D35400";
    static final String[] PALETA = {
            "#2E86C1", "#28B463", "#8E44AD", "#CA6F1E", "#C0392B",
            "#17A589", "#B7950B", "#5D6D7E", "#E74C3C", "#1F618D"
    };
    static final Color GRIS = new Color(120, 120, 120);

    // ---------- UI ----------
    private final JTextPane areaXat = new JTextPane();
    private final StyledDocument doc = areaXat.getStyledDocument();
    private final DefaultListModel<InfoUsuari> modelUsuaris = new DefaultListModel<>();
    private final JList<InfoUsuari> llistaUsuaris = new JList<>(modelUsuaris);
    private final JTextField campText = new JTextField();
    private final JButton botoEnviar = new JButton("Enviar");
    private final JLabel estat = new JLabel(" ");

    // ---------- Estat ----------
    private String nom;
    private final boolean esHost;
    private Servidor servidor;           // només si som host
    private Socket socket;               // només si som client
    private PrintWriter sortida;         // només si som client
    private volatile boolean expulsat = false;

    /** Info d'un usuari tal com es mostra a la llista (la reben tots). */
    static class InfoUsuari {
        final String nom, rol;
        final Color color;
        final boolean silenciat;

        InfoUsuari(String nom, String rol, Color color, boolean silenciat) {
            this.nom = nom;
            this.rol = rol;
            this.color = color;
            this.silenciat = silenciat;
        }
    }

    public XatTCP(String nom, boolean esHost) {
        super("Xat TCP - " + nom + (esHost ? " (host)" : ""));
        this.nom = nom;
        this.esHost = esHost;
        construirUI();
    }

    // =====================================================================
    //  UI
    // =====================================================================

    private void construirUI() {
        areaXat.setEditable(false);
        areaXat.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        campText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        // Panell inferior: escriure + estat
        JPanel baix = new JPanel(new BorderLayout(5, 5));
        baix.add(campText, BorderLayout.CENTER);
        baix.add(botoEnviar, BorderLayout.EAST);
        baix.add(estat, BorderLayout.SOUTH);
        baix.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Panell dret: usuaris connectats (+ admin si som host)
        llistaUsuaris.setCellRenderer(new RendererUsuari());
        llistaUsuaris.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel dreta = new JPanel(new BorderLayout(5, 5));
        dreta.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 5));
        dreta.setPreferredSize(new Dimension(210, 0));
        dreta.add(new JLabel("Connectats"), BorderLayout.NORTH);
        dreta.add(new JScrollPane(llistaUsuaris), BorderLayout.CENTER);
        if (esHost) dreta.add(construirPanellAdmin(), BorderLayout.SOUTH);

        JScrollPane scrollXat = new JScrollPane(areaXat);
        scrollXat.setBorder(BorderFactory.createEmptyBorder(5, 5, 0, 5));

        add(scrollXat, BorderLayout.CENTER);
        add(dreta, BorderLayout.EAST);
        add(baix, BorderLayout.SOUTH);

        botoEnviar.addActionListener(e -> enviar());
        campText.addActionListener(e -> enviar());
        setEnviamentActiu(false);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (esHost && servidor != null) servidor.aturar();
                tancarSocket();
                System.exit(0);
            }
        });

        setSize(750, 550);
        setLocationRelativeTo(null);
    }

    private JPanel construirPanellAdmin() {
        JPanel admin = new JPanel(new GridLayout(0, 1, 4, 4));
        admin.setBorder(BorderFactory.createTitledBorder("Administració"));

        JButton bRol = new JButton("Canviar rol");
        JButton bColor = new JButton("Canviar color");
        JButton bSilenci = new JButton("Silenciar / Activar");
        JButton bExpulsar = new JButton("Expulsar");
        JButton bNetejar = new JButton("Netejar xat");

        bRol.addActionListener(e -> {
            InfoUsuari u = seleccionat();
            if (u == null) return;
            JComboBox<String> combo = new JComboBox<>(new String[]{"", "Admin", "Moderador", "VIP", "Membre"});
            combo.setEditable(true);
            combo.setSelectedItem(u.rol);
            int r = JOptionPane.showConfirmDialog(this, combo, "Rol de " + u.nom + " (pots escriure'n un)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r == JOptionPane.OK_OPTION) {
                servidor.canviarRol(u.nom, String.valueOf(combo.getSelectedItem()));
            }
        });

        bColor.addActionListener(e -> {
            InfoUsuari u = seleccionat();
            if (u == null) return;
            Color c = JColorChooser.showDialog(this, "Color de " + u.nom, u.color);
            if (c != null) servidor.canviarColor(u.nom, aHex(c));
        });

        bSilenci.addActionListener(e -> {
            InfoUsuari u = seleccionat();
            if (u == null) return;
            if (u.nom.equals(nom)) {
                JOptionPane.showMessageDialog(this, "No et pots silenciar a tu mateix.");
                return;
            }
            servidor.commutarSilenci(u.nom);
        });

        bExpulsar.addActionListener(e -> {
            InfoUsuari u = seleccionat();
            if (u == null) return;
            if (u.nom.equals(nom)) {
                JOptionPane.showMessageDialog(this, "No et pots expulsar a tu mateix.");
                return;
            }
            int r = JOptionPane.showConfirmDialog(this, "Expulsar " + u.nom + "?", "Expulsar",
                    JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) servidor.expulsar(u.nom);
        });

        bNetejar.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(this, "Netejar el xat per a tothom?", "Netejar",
                    JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) servidor.netejar();
        });

        admin.add(bRol);
        admin.add(bColor);
        admin.add(bSilenci);
        admin.add(bExpulsar);
        admin.add(bNetejar);
        return admin;
    }

    private InfoUsuari seleccionat() {
        InfoUsuari u = llistaUsuaris.getSelectedValue();
        if (u == null) JOptionPane.showMessageDialog(this, "Selecciona primer un usuari de la llista.");
        return u;
    }

    /** Pinta cada usuari amb el seu color, rol i si està silenciat. */
    static class RendererUsuari extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            InfoUsuari u = (InfoUsuari) value;
            String text = "● " + u.nom;
            if (!u.rol.isEmpty()) text += "  [" + u.rol + "]";
            if (u.silenciat) text += "  (silenciat)";
            setText(text);
            if (!isSelected) setForeground(u.color);
            setFont(getFont().deriveFont(Font.BOLD));
            return this;
        }
    }

    // =====================================================================
    //  Enviar i rebre (comú a host i client)
    // =====================================================================

    private void enviar() {
        String text = campText.getText().trim();
        if (text.isEmpty()) return;
        if (esHost) {
            servidor.missatgeDelHost(text);
        } else if (sortida != null) {
            sortida.println("MSG|" + text);
        }
        campText.setText("");
    }

    /** Interpreta una línia del protocol. S'executa sempre al fil de Swing (EDT). */
    private void processarLinia(String linia) {
        int sep = linia.indexOf('|');
        String ordre = sep < 0 ? linia : linia.substring(0, sep);
        String resta = sep < 0 ? "" : linia.substring(sep + 1);

        switch (ordre) {
            case "MSG": {
                String[] p = resta.split("\\|", 5); // nom, rol, color, hora, text
                if (p.length == 5) pintarMissatge(p[0], p[1], parseColor(p[2]), p[3], p[4]);
                break;
            }
            case "SYS":
                pintarSistema(resta);
                break;
            case "USERS":
                actualitzarUsuaris(resta);
                break;
            case "CLEAR":
                areaXat.setText("");
                pintarSistema("El host ha netejat el xat.");
                break;
            case "WELCOME":
                nom = resta;
                setTitle("Xat TCP - " + nom);
                break;
            case "KICKED":
                expulsat = true;
                pintarSistema(resta);
                setEnviamentActiu(false);
                mostrarEstat("Expulsat");
                JOptionPane.showMessageDialog(this, resta, "Expulsat", JOptionPane.WARNING_MESSAGE);
                break;
            default:
                // ordre desconeguda: s'ignora
        }
    }

    private void pintarMissatge(String autor, String rol, Color color, String hora, String text) {
        afegir("[" + hora + "] ", GRIS, false, false);
        if (!rol.isEmpty()) afegir("[" + rol + "] ", color, true, false);
        afegir(autor + ": ", color, true, false);
        afegir(text + "\n", Color.BLACK, false, false);
    }

    private void pintarSistema(String text) {
        afegir("— " + text + "\n", GRIS, false, true);
    }

    private void afegir(String text, Color color, boolean negreta, boolean cursiva) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, negreta);
        StyleConstants.setItalic(a, cursiva);
        try {
            doc.insertString(doc.getLength(), text, a);
        } catch (BadLocationException ignored) {
        }
        areaXat.setCaretPosition(doc.getLength());
    }

    private void actualitzarUsuaris(String dades) {
        InfoUsuari sel = llistaUsuaris.getSelectedValue();
        String nomSel = sel == null ? null : sel.nom;
        modelUsuaris.clear();
        if (dades.isEmpty()) return;
        for (String entrada : dades.split(";")) {
            String[] c = entrada.split(",", -1); // nom, rol, color, silenciat
            if (c.length != 4) continue;
            InfoUsuari u = new InfoUsuari(c[0], c[1], parseColor(c[2]), "1".equals(c[3]));
            modelUsuaris.addElement(u);
            if (u.nom.equals(nomSel)) llistaUsuaris.setSelectedIndex(modelUsuaris.size() - 1);
        }
    }

    private void mostrarEstat(String m) {
        SwingUtilities.invokeLater(() -> estat.setText(m));
    }

    private void setEnviamentActiu(boolean actiu) {
        campText.setEnabled(actiu);
        botoEnviar.setEnabled(actiu);
        if (actiu) campText.requestFocusInWindow();
    }

    // =====================================================================
    //  Mode CLIENT
    // =====================================================================

    public void connectar(String host, int port) {
        new Thread(() -> {
            try {
                mostrarEstat("Connectant a " + host + ":" + port + "...");
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                socket = s;
                sortida = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader entrada = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                sortida.println("HELLO|" + nom);
                mostrarEstat("Connectat a " + host + ":" + port);
                SwingUtilities.invokeLater(() -> setEnviamentActiu(true));

                String l;
                while ((l = entrada.readLine()) != null) {
                    final String linia = l;
                    SwingUtilities.invokeLater(() -> processarLinia(linia));
                }
            } catch (SocketTimeoutException e) {
                mostrarEstat("Timeout: no arriba al host (firewall o xarxa diferent).");
                return;
            } catch (ConnectException e) {
                mostrarEstat("Connexió rebutjada: el host no està escoltant en aquest port.");
                return;
            } catch (IOException e) {
                // connexió tallada
            }
            SwingUtilities.invokeLater(() -> {
                setEnviamentActiu(false);
                if (!expulsat) {
                    pintarSistema("Desconnectat del servidor.");
                    estat.setText("Desconnectat");
                }
            });
        }, "client").start();
    }

    private void tancarSocket() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // =====================================================================
    //  Mode HOST (servidor + participant)
    // =====================================================================

    public void hostejar(int port) throws IOException {
        servidor = new Servidor();
        servidor.iniciar(port);
        setEnviamentActiu(true);
        estat.setText("Hostejant al port " + port + "  ·  La teva IP: " + ipsLocals());
    }

    private class Servidor {
        private ServerSocket serverSocket;
        private final Map<String, Usuari> usuaris = new LinkedHashMap<>(); // protegit amb synchronized
        private int comptadorColor = 0;

        class Usuari {
            final String nom;
            volatile String rol;
            volatile String color;
            volatile boolean silenciat;
            final Connexio con; // null = el host

            Usuari(String nom, String rol, String color, Connexio con) {
                this.nom = nom;
                this.rol = rol;
                this.color = color;
                this.con = con;
            }
        }

        void iniciar(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            synchronized (usuaris) {
                usuaris.put(nom, new Usuari(nom, "Host", COLOR_HOST, null));
            }
            enviarUsuaris();
            pintarSistema("Xat creat. Esperant usuaris...");

            Thread acceptador = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        new Connexio(serverSocket.accept()).start();
                    } catch (IOException e) {
                        break; // ServerSocket tancat
                    }
                }
            }, "acceptador");
            acceptador.setDaemon(true);
            acceptador.start();
        }

        /** Envia una línia a tots els clients i també la mostra a la finestra del host. */
        void broadcast(String linia) {
            List<Connexio> destins = new ArrayList<>();
            synchronized (usuaris) {
                for (Usuari u : usuaris.values()) if (u.con != null) destins.add(u.con);
            }
            for (Connexio c : destins) c.enviar(linia);
            SwingUtilities.invokeLater(() -> processarLinia(linia));
        }

        void enviarUsuaris() {
            StringJoiner sj = new StringJoiner(";");
            synchronized (usuaris) {
                for (Usuari u : usuaris.values()) {
                    sj.add(u.nom + "," + u.rol + "," + u.color + "," + (u.silenciat ? "1" : "0"));
                }
            }
            broadcast("USERS|" + sj);
        }

        String formatMissatge(Usuari u, String text) {
            return "MSG|" + u.nom + "|" + u.rol + "|" + u.color + "|" + LocalTime.now().format(HORA) + "|" + text;
        }

        private Usuari buscar(String n) {
            synchronized (usuaris) {
                return usuaris.get(n);
            }
        }

        // ---------- Accions del host ----------

        void missatgeDelHost(String text) {
            Usuari host = buscar(nom);
            if (host != null) broadcast(formatMissatge(host, text));
        }

        void canviarRol(String n, String rol) {
            Usuari u = buscar(n);
            if (u == null) return;
            u.rol = sanejar(rol);
            broadcast("SYS|" + n + (u.rol.isEmpty() ? " ja no té rol." : " ara és [" + u.rol + "]."));
            enviarUsuaris();
        }

        void canviarColor(String n, String hex) {
            Usuari u = buscar(n);
            if (u == null) return;
            u.color = hex;
            enviarUsuaris();
        }

        void commutarSilenci(String n) {
            Usuari u = buscar(n);
            if (u == null || u.con == null) return;
            u.silenciat = !u.silenciat;
            broadcast("SYS|" + n + (u.silenciat ? " ha estat silenciat." : " ja pot tornar a parlar."));
            enviarUsuaris();
        }

        void expulsar(String n) {
            Usuari u = buscar(n);
            if (u == null || u.con == null) return;
            u.con.expulsat = true;
            u.con.enviar("KICKED|Has estat expulsat pel host.");
            u.con.tancar(); // el fil de la connexió avisa a la resta
        }

        void netejar() {
            broadcast("CLEAR");
        }

        void aturar() {
            broadcast("SYS|El host ha tancat el xat.");
            List<Connexio> totes = new ArrayList<>();
            synchronized (usuaris) {
                for (Usuari u : usuaris.values()) if (u.con != null) totes.add(u.con);
            }
            for (Connexio c : totes) c.tancar();
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        // ---------- Una connexió per cada client ----------

        class Connexio extends Thread {
            private final Socket s;
            private PrintWriter out;
            private Usuari usuari;
            volatile boolean expulsat = false;

            Connexio(Socket s) {
                this.s = s;
                setDaemon(true);
            }

            void enviar(String linia) {
                if (out != null) out.println(linia);
            }

            void tancar() {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }

            @Override
            public void run() {
                try {
                    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                    // Primer missatge obligatori: HELLO|nom
                    s.setSoTimeout(10000);
                    String hello = in.readLine();
                    if (hello == null || !hello.startsWith("HELLO|")) return;
                    s.setSoTimeout(0);

                    String demanat = sanejar(hello.substring(6));
                    if (demanat.isEmpty()) demanat = "Anònim";
                    synchronized (usuaris) {
                        String unic = nomUnic(demanat);
                        usuari = new Usuari(unic, "", PALETA[comptadorColor++ % PALETA.length], this);
                        usuaris.put(unic, usuari);
                    }
                    enviar("WELCOME|" + usuari.nom);
                    broadcast("SYS|" + usuari.nom + " s'ha unit al xat.");
                    enviarUsuaris();

                    String l;
                    while ((l = in.readLine()) != null) {
                        if (!l.startsWith("MSG|")) continue;
                        String text = l.substring(4).trim();
                        if (text.isEmpty()) continue;
                        if (usuari.silenciat) {
                            enviar("SYS|Estàs silenciat pel host. El teu missatge no s'ha enviat.");
                            continue;
                        }
                        broadcast(formatMissatge(usuari, text));
                    }
                } catch (IOException ignored) {
                    // client desconnectat
                } finally {
                    tancar();
                    if (usuari != null) {
                        synchronized (usuaris) {
                            usuaris.remove(usuari.nom);
                        }
                        broadcast("SYS|" + usuari.nom + (expulsat ? " ha estat expulsat pel host." : " ha sortit del xat."));
                        enviarUsuaris();
                    }
                }
            }
        }

        /** Si el nom ja existeix, hi afegeix un número (Pau, Pau2, Pau3...). Cridar dins synchronized. */
        private String nomUnic(String base) {
            if (!usuaris.containsKey(base)) return base;
            int i = 2;
            while (usuaris.containsKey(base + i)) i++;
            return base + i;
        }
    }

    // =====================================================================
    //  Utilitats
    // =====================================================================

    /** Treu els caràcters que fa servir el protocol i limita la llargada. */
    static String sanejar(String s) {
        String net = s.replaceAll("[|;,\\r\\n]", "").trim();
        return net.length() > 20 ? net.substring(0, 20) : net;
    }

    static Color parseColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return GRIS;
        }
    }

    static String aHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** IPv4 locals, perquè el host sàpiga quina passar als altres. */
    static String ipsLocals() {
        StringJoiner sj = new StringJoiner(", ");
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> adreces = ni.getInetAddresses();
                while (adreces.hasMoreElements()) {
                    InetAddress a = adreces.nextElement();
                    if (a instanceof Inet4Address) sj.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        return sj.length() == 0 ? "?" : sj.toString();
    }

    // =====================================================================
    //  main
    // =====================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String nomEntrat = JOptionPane.showInputDialog(null, "El teu nom:", "Xat TCP", JOptionPane.QUESTION_MESSAGE);
            if (nomEntrat == null) return;
            String nom = sanejar(nomEntrat);
            if (nom.isEmpty()) return;

            String[] opcions = {"Hostejar un xat", "Unir-me a un xat"};
            int mode = JOptionPane.showOptionDialog(null, "Què vols fer?", "Xat TCP",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opcions, opcions[0]);
            if (mode < 0) return;

            String portText = JOptionPane.showInputDialog(null, "Port:", String.valueOf(PORT_PER_DEFECTE));
            if (portText == null) return;
            int port;
            try {
                port = Integer.parseInt(portText.trim());
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(null, "Port invàlid.");
                return;
            }

            if (mode == 0) {
                XatTCP finestra = new XatTCP(nom, true);
                try {
                    finestra.hostejar(port);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(null, "No s'ha pogut obrir el port " + port + ": " + e.getMessage());
                    System.exit(1);
                }
                finestra.setVisible(true);
            } else {
                String host = JOptionPane.showInputDialog(null, "IP del host:", "192.168.3.122");
                if (host == null || host.isBlank()) return;
                XatTCP finestra = new XatTCP(nom, false);
                finestra.setVisible(true);
                finestra.connectar(host.trim(), port);
            }
        });
    }
}
