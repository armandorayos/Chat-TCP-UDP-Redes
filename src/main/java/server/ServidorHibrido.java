/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ServidorHibrido {
    private static final int MAX_CLIENTES = 5;
    private static final int TIMEOUT_MS = 20000;
    
    private static Map<String, PrintWriter> mapaTCP = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, String> mapaUDP = Collections.synchronizedMap(new HashMap<>()); 
    private static Map<String, Long> ultimaActividad = Collections.synchronizedMap(new HashMap<>());
    private static DatagramSocket udpSocketGlobal;

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Puerto del servidor: ");
        int puerto = sc.nextInt();

        new Thread(() -> iniciarTCP(puerto)).start();
        new Thread(() -> iniciarUDP(puerto)).start();
        new Thread(() -> hiloLimpiador()).start();

        System.out.println("Servidor iniciado en puerto " + puerto);
    }

    private static boolean nombreOcupado(String nombre) {
        return mapaTCP.containsKey(nombre) || mapaUDP.containsValue(nombre);
    }

    private static void hiloLimpiador() {
        while (true) {
            try {
                Thread.sleep(3000);
                long ahora = System.currentTimeMillis();
                synchronized (ultimaActividad) {
                    Iterator<Map.Entry<String, Long>> it = ultimaActividad.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Long> entry = it.next();
                        if (ahora - entry.getValue() > TIMEOUT_MS) {
                            String id = entry.getKey();
                            if (mapaTCP.containsKey(id)) {
                                System.out.println("Usuario [TCP] " + id+ " desconectado");
                                mapaTCP.remove(id);
                            } else if (mapaUDP.containsKey(id)) {
                                System.out.println("Usuario [UDP] " + mapaUDP.get(id)+ " desconectado");
                                mapaUDP.remove(id);
                            }
                            it.remove();
                        }
                    }
                }
            } catch (Exception e) { }
        }
    }

    public static void iniciarTCP(int puerto) {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> manejarClienteTCP(socket)).start();
            }
        } catch (IOException e) { }
    }

    private static void manejarClienteTCP(Socket socket) {
        String nombre = "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            
            while (true) {
                nombre = in.readLine();
                if (nombre == null) return;
                if (mapaTCP.size() + mapaUDP.size() >= MAX_CLIENTES) {
                    out.println("ERROR_SERVIDOR_LLENO");
                } else if (nombreOcupado(nombre)) {
                    out.println("ERROR_YA_EXISTE");
                } else {
                    out.println("OK");
                    break;
                }
            }

            mapaTCP.put(nombre, out);
            ultimaActividad.put(nombre, System.currentTimeMillis());
            System.out.println("Usuario [TCP] " + nombre + " conectado.");

            String msg;
            while ((msg = in.readLine()) != null) {
                ultimaActividad.put(nombre, System.currentTimeMillis());
                if (msg.equalsIgnoreCase("exit")) break;
                if (msg.equals("HEARTBEAT")) continue;
                procesarMensaje(nombre, msg);
            }
        } catch (IOException e) { 
        } finally {
            if (nombre != null && !nombre.isEmpty()) {
                mapaTCP.remove(nombre);
                ultimaActividad.remove(nombre);
                System.out.println("Usuario [TCP] " + nombre + " desconectado.");
            }
        }
    }

    public static void iniciarUDP(int puerto) {
        try {
            udpSocketGlobal = new DatagramSocket(puerto);
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                udpSocketGlobal.receive(p);
                String id = p.getAddress().getHostAddress() + ":" + p.getPort();
                String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();

                if (txt.startsWith("JOIN:")) {
                    String n = txt.substring(5);
                    byte[] resp;
                    if (mapaTCP.size() + mapaUDP.size() >= MAX_CLIENTES) resp = "ERROR_LLENO".getBytes();
                    else if (nombreOcupado(n)) resp = "ERROR_EXISTE".getBytes();
                    else {
                        mapaUDP.put(id, n);
                        ultimaActividad.put(id, System.currentTimeMillis());
                        resp = "OK".getBytes();
                        System.out.println("Usuario [UDP] " + n + " conectado.");
                    }
                    udpSocketGlobal.send(new DatagramPacket(resp, resp.length, p.getAddress(), p.getPort()));
                } else if (txt.equals("HEARTBEAT")) {
                    ultimaActividad.put(id, System.currentTimeMillis());
                } else if (txt.endsWith(":exit")) {
                    String n = mapaUDP.remove(id);
                    ultimaActividad.remove(id);
                    if (n != null) System.out.println("Usuario [UDP]: " + n + " desconectado.");
                } else {
                    String n = mapaUDP.get(id);
                    if (n != null) {
                        ultimaActividad.put(id, System.currentTimeMillis());
                        procesarMensaje(n, txt);
                    }
                }
            }
        } catch (IOException e) { }
    }

    private static void procesarMensaje(String de, String m) {
        if (m.startsWith("/priv ")) {
            String resto = m.substring(6).trim();
            int primerEspacio = resto.indexOf(" ");
            if (primerEspacio != -1) {
                String para = resto.substring(0, primerEspacio);
                String contenido = resto.substring(primerEspacio).trim();
                enviarPrivado(de, para, contenido);
                return;
            }
        }
        
        // Formato unificado
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a"));
        String f = "\n" + de + " " + fecha + ".:\n" + m + "\n";
        
        System.out.println(f);
        enviarATodos(f);
    }

    private static void enviarPrivado(String de, String para, String texto) {
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
        String msg = "\n[PRIVADO de " + de + "] " + fecha + ":\n" + texto + "\n";
        
        PrintWriter out = mapaTCP.get(para);
        if (out != null) {
            out.println(msg);
            return;
        }

        synchronized (mapaUDP) {
            for (Map.Entry<String, String> entry : mapaUDP.entrySet()) {
                if (entry.getValue().equals(para)) {
                    try {
                        byte[] d = msg.getBytes(StandardCharsets.UTF_8);
                        String[] p = entry.getKey().split(":");
                        udpSocketGlobal.send(new DatagramPacket(d, d.length, InetAddress.getByName(p[0]), Integer.parseInt(p[1])));
                        return;
                    } catch (IOException e) { }
                }
            }
        }
    }

    private static void enviarATodos(String msg) {
        synchronized(mapaTCP) {
            for (PrintWriter out : mapaTCP.values()) out.println(msg);
        }
        synchronized(mapaUDP) {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            for (String id : mapaUDP.keySet()) {
                try {
                    String[] p = id.split(":");
                    udpSocketGlobal.send(new DatagramPacket(data, data.length, InetAddress.getByName(p[0]), Integer.parseInt(p[1])));
                } catch (IOException e) { }
            }
        }
    }
}