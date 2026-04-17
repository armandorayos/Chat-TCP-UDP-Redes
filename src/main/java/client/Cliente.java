/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

/**
 *
 * @author armandorayos
 */
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Cliente {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Nombre de usuario: ");
        String nombre = sc.nextLine();
        System.out.print("IP Servidor: ");
        String ip = sc.nextLine();
        System.out.print("Puerto: ");
        int puerto = sc.nextInt();
        sc.nextLine(); 

        System.out.println("Elija protocolo: 1. TCP | 2. UDP");
        int opt = sc.nextInt();
        sc.nextLine();

        if (opt == 1) iniciarTCP(ip, puerto, nombre, sc);
        else iniciarUDP(ip, puerto, nombre, sc);
    }

    private static void iniciarTCP(String ip, int puerto, String nombre, Scanner sc) {
        try (Socket socket = new Socket(ip, puerto);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            out.println(nombre);
            String respuesta = in.readLine();
            
            if (respuesta != null && respuesta.startsWith("ERROR")) {
                System.out.println(respuesta);
                return;
            }

            System.out.println("--- Conectado vía TCP como " + nombre + " ---");
            System.out.println("(Escribe 'exit' para salir)");
            
            Thread hiloLectura = new Thread(() -> {
                try {
                    String r;
                    while ((r = in.readLine()) != null) System.out.println(r);
                } catch (IOException e) { System.out.println("Desconectado del servidor."); }
            });
            hiloLectura.start();

            String msg;
            while (sc.hasNextLine()) {
                msg = sc.nextLine();
                out.println(msg);
                if (msg.equalsIgnoreCase("exit")) break;
            }
        } catch (IOException e) { System.out.println("Error de conexión."); }
    }

    private static void iniciarUDP(String ip, int puerto, String nombre, Scanner sc) {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress addr = InetAddress.getByName(ip);
            
            String join = "JOIN:" + nombre;
            socket.send(new DatagramPacket(join.getBytes(), join.length(), addr, puerto));

            byte[] bufConf = new byte[1024];
            DatagramPacket pConf = new DatagramPacket(bufConf, bufConf.length);
            socket.receive(pConf);
            String res = new String(pConf.getData(), 0, pConf.getLength()).trim();

            if (res.startsWith("ERROR")) {
                System.out.println(res);
                return;
            }

            System.out.println("--- Conectado vía UDP como " + nombre + " ---");
            System.out.println("(Escribe 'exit' para salir)");

            Thread hiloLectura = new Thread(() -> {
                try {
                    byte[] buf = new byte[1024];
                    while (true) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        System.out.println(new String(p.getData(), 0, p.getLength()));
                    }
                } catch (IOException e) { }
            });
            hiloLectura.start();

            String msg;
            while (sc.hasNextLine()) {
                msg = sc.nextLine();
                if (msg.equalsIgnoreCase("exit")) {
                    String exitMsg = nombre + ":exit";
                    socket.send(new DatagramPacket(exitMsg.getBytes(), exitMsg.length(), addr, puerto));
                    break;
                }
                socket.send(new DatagramPacket(msg.getBytes(), msg.length(), addr, puerto));
            }
        } catch (IOException e) { System.out.println("Error de conexión."); }
        System.exit(0);
    }
}
