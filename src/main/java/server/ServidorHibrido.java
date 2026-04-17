/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package server;

/**
 *
 * @author armandorayos
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ServidorHibrido {
    private static Map<String, PrintWriter> mapaTCP = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, String> mapaUDP = Collections.synchronizedMap(new HashMap<>()); 
    private static DatagramSocket udpSocketGlobal;

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Puerto del servidor: ");
        int puerto = sc.nextInt();

        new Thread(() -> iniciarTCP(puerto)).start();
        new Thread(() -> iniciarUDP(puerto)).start();

        System.out.println("Servidor Chat Universal iniciado en puerto " + puerto);
    }

    private static String getFechaFormateada() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a"));
    }

    private static boolean nombreExiste(String nombre) {
        return mapaTCP.containsKey(nombre) || mapaUDP.containsValue(nombre);
    }

    private static void enviarATodos(String msg) {
        synchronized (mapaTCP) {
            Iterator<Map.Entry<String, PrintWriter>> it = mapaTCP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PrintWriter> entry = it.next();
                entry.getValue().println(msg);
            }
        }
        synchronized (mapaUDP) {
            byte[] data = msg.getBytes();
            for (String id : mapaUDP.keySet()) {
                try {
                    String[] partes = id.split(":");
                    InetAddress addr = InetAddress.getByName(partes[0]);
                    int port = Integer.parseInt(partes[1]);
                    udpSocketGlobal.send(new DatagramPacket(data, data.length, addr, port));
                } catch (IOException e) { }
            }
        }
    }

    public static void iniciarTCP(int puerto) {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> manejarClienteTCP(socket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void manejarClienteTCP(Socket socket) {
        String nombre = "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            nombre = in.readLine();
            if (nombre == null || nombreExiste(nombre)) {
                out.println("ERROR: El nombre de usuario ya existe o es invalido.");
                socket.close();
                return;
            }

            out.println("OK");
            mapaTCP.put(nombre, out);
            System.out.println("[TCP] " + nombre + " conectado.");

            String msg;
            while ((msg = in.readLine()) != null) {
                if (msg.equalsIgnoreCase("exit")) break;
                String formato = nombre + " " + getFechaFormateada() + ":\n" + msg;
                System.out.println(formato);
                enviarATodos(formato);
            }
        } catch (IOException e) { 
        } finally {
            if (nombre != null && !nombre.isEmpty()) {
                mapaTCP.remove(nombre);
                System.out.println("[TCP] Usuario " + nombre + " fuera.");
            }
        }
    }

    public static void iniciarUDP(int puerto) {
        try {
            udpSocketGlobal = new DatagramSocket(puerto);
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocketGlobal.receive(packet);
                
                String idCliente = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                String recibido = new String(packet.getData(), 0, packet.getLength()).trim();

                if (recibido.startsWith("JOIN:")) {
                    String nombreSolicitado = recibido.substring(5);
                    if (nombreExiste(nombreSolicitado)) {
                        byte[] err = "ERROR: Nombre ocupado".getBytes();
                        udpSocketGlobal.send(new DatagramPacket(err, err.length, packet.getAddress(), packet.getPort()));
                    } else {
                        mapaUDP.put(idCliente, nombreSolicitado);
                        byte[] ok = "OK".getBytes();
                        udpSocketGlobal.send(new DatagramPacket(ok, ok.length, packet.getAddress(), packet.getPort()));
                        System.out.println("[UDP] " + nombreSolicitado + " conectado.");
                    }
                } else if (recibido.endsWith(":exit")) {
                    mapaUDP.remove(idCliente);
                    System.out.println("[UDP] Cliente removido: " + idCliente);
                } else {
                    String nombreUdp = mapaUDP.get(idCliente);
                    if (nombreUdp != null) {
                        String formato = nombreUdp + " " + getFechaFormateada() + ":\n" + recibido;
                        System.out.println(formato);
                        enviarATodos(formato);
                    }
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }
}