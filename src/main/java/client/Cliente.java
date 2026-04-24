/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class Cliente {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("IP Servidor: ");
        String ip = sc.nextLine();
        System.out.print("Puerto: ");
        int puerto = sc.nextInt();
        System.out.println("Protocolo: 1. TCP | 2. UDP");
        int opt = sc.nextInt();
        sc.nextLine();

        if (opt == 1) iniciarTCP(ip, puerto, sc);
        else iniciarUDP(ip, puerto, sc);
    }

    private static void iniciarTCP(String ip, int puerto, Scanner sc) {
        try (Socket socket = new Socket(ip, puerto);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            
            String nombre;
            while (true) {
                System.out.print("Nombre de usuario: ");
                nombre = sc.nextLine().trim();
                out.println(nombre);
                String res = in.readLine();
                if ("OK".equals(res)) break;
                System.out.println(">> Error: " + res);
            }

            System.out.println("--- Conectado vía TCP ---");
            lanzarHilos(out, null, null, 0, in, null, "TCP");

            while (true) {
                String m = sc.nextLine();
                out.println(m);
                if (m.equalsIgnoreCase("exit")) break;
            }
        } catch (Exception e) { System.out.println("Conexión perdida."); }
    }

    private static void iniciarUDP(String ip, int puerto, Scanner sc) {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress addr = InetAddress.getByName(ip);
            String n;
            while (true) {
                System.out.print("Nombre de usuario: ");
                n = sc.nextLine().trim();
                byte[] j = ("JOIN:" + n).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(j, j.length, addr, puerto));
                
                byte[] b = new byte[1024];
                DatagramPacket p = new DatagramPacket(b, b.length);
                socket.receive(p);
                String res = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if ("OK".equals(res)) break;
                System.out.println(">> Error: " + res);
            }

            System.out.println("--- Conectado vía UDP ---");
            lanzarHilos(null, socket, addr, puerto, null, socket, "UDP");

            while (true) {
                String m = sc.nextLine();
                if (m.equalsIgnoreCase("exit")) {
                    byte[] b = (n + ":exit").getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(b, b.length, addr, puerto));
                    break;
                }
                byte[] d = m.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(d, d.length, addr, puerto));
            }
        } catch (Exception e) { }
        System.exit(0);
    }

    private static void lanzarHilos(PrintWriter out, DatagramSocket ds, InetAddress addr, int p, BufferedReader in, DatagramSocket dsRec, String proto) {
        new Thread(() -> {
            try {
                while(true) { 
                    Thread.sleep(10000); 
                    if (proto.equals("TCP")) out.println("HEARTBEAT");
                    else {
                        byte[] b = "HEARTBEAT".getBytes();
                        ds.send(new DatagramPacket(b, b.length, addr, p));
                    }
                }
            } catch(Exception e){}
        }).start();

        new Thread(() -> {
            try {
                if (proto.equals("TCP")) {
                    String r;
                    while ((r = in.readLine()) != null) {
                        System.out.println(r);
                    }
                } else {
                    byte[] buf = new byte[2048];
                    while (true) {
                        DatagramPacket pack = new DatagramPacket(buf, buf.length);
                        dsRec.receive(pack);
                        System.out.println(new String(pack.getData(), 0, pack.getLength(), StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) { }
        }).start();
    }
}