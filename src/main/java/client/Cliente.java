/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Registra y envia mensajes desde la consola
 * y recibe mensajes del servidor al mismo tiempo (Multihilo).
 */
public class Cliente {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String ip = "";
        int puerto = -1;
        int op = -1;

        // Validamos IP/Host
        while (true) {
            System.out.print("IP Servidor: ");
            ip = sc.nextLine().trim();
            try {
                // InetAddress.getByName valida si la IP es correcta o el host existe
                InetAddress.getByName(ip);
                break; // Si llega aquí, la IP es válida
            } catch (Exception e) {
                System.out.println(">> Error: Dirección IP o Host no válido. Intenta de nuevo.");
            }
        }

        // Validamos puerto
        while (true) {
            System.out.print("Puerto: ");
            try {
                puerto = Integer.parseInt(sc.nextLine());
                break; // Si es un número válido, salimos del bucle
            } catch (NumberFormatException e) {
                System.out.println(">> Error: El puerto debe ser un número entero.");
            }
        }

        // Validamos opcion de protocolo seleccionado
        do {
            System.out.println("Protocolo: 1. TCP | 2. UDP");
            try {
                op = Integer.parseInt(sc.nextLine());
                if (op != 1 && op != 2) {
                    System.out.println(">> Error: Selecciona 1 para TCP o 2 para UDP.");
                }
            } catch (NumberFormatException e) {
                System.out.println(">> Error: Debes ingresar un número (1 o 2).");
            }
        } while (op != 1 && op != 2);

        // Iniciamos la conexion
        if (op == 1) {
            iniciarTCP(ip, puerto, sc);
        } else {
            iniciarUDP(ip, puerto, sc);
        }
    }

    /**
     * Crea una conexión persistente. Mantiene un hilo de envío y otro de escucha.
     */
    private static void iniciarTCP(String ip, int puerto, Scanner sc) {
        
        try (Socket socket = new Socket()) { // Ajuste: Creamos el socket vacío primero
            
            // Damos 2 segundos y evitamos que el porgrama se quede colgado si el puerto no responde
            socket.connect(new InetSocketAddress(ip, puerto), 2000); 
            socket.setSoTimeout(2000); 

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                
                String nombre;
                while (true) {
                    System.out.print("Nombre de usuario: ");
                    nombre = sc.nextLine().trim();
                    out.println(nombre); // Envíamos el nombre al servidor
                    String res = in.readLine(); // Esperamos validación: OK o ERROR
                    if ("OK".equals(res)) break;//Registro exitoso
                    System.out.println(">> Error: " + res);
                }

                // Una vez registrados, quitamos el timeout para que el hilo de escucha pueda esperar mensajes libremente
                socket.setSoTimeout(0);

                System.out.println("--- Conectado vía TCP ---");
                // Iniciamos los hilos
                lanzarHilos(out, null, null, 0, in, "TCP");

                // Bucle de leectura en consola y envio de datos al servidor
                while (true) {
                    String mensaje = sc.nextLine();
                    out.println(mensaje);
                    if (mensaje.equalsIgnoreCase("exit")) break;
                }
            }
        } catch (Exception e) { 
            // Mensaje informativo si el puerto es erróneo o el servidor está caído
            System.out.println(">> Error: No se pudo conectar al servidor"); 
        }
    }

    /**
     * Usa Datagramas para registrarse y enviar mensajes.
     */
    private static void iniciarUDP(String ip, int puerto, Scanner sc) {
        try (
            //Creamos el buzon de comunicacion
            DatagramSocket socket = new DatagramSocket()) {
            
            //Si el servidor no responde en 2 segundos, lanzamos error
            socket.setSoTimeout(2000); 
            
            InetAddress direccionIP = InetAddress.getByName(ip);//Preparamos y validamos la ip del servidor//
            String n;
            while (true) {
                System.out.print("Nombre de usuario: ");
                n = sc.nextLine().trim();
                byte[] j = ("JOIN:" + n).getBytes(StandardCharsets.UTF_8);//Preparamos datos de rigistro con el comando JOIN//
                // Envíamos paquete de registro al servidor
                socket.send(new DatagramPacket(j, j.length, direccionIP, puerto));
                
                //Preparamos donde guardar la respuesta del servidor
                byte[] b = new byte[1024];
                DatagramPacket p = new DatagramPacket(b, b.length);
                socket.receive(p); // Esperamos y recibimos respuesta del servidor (si falla, salta al catch final)
                
                String res = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if ("OK".equals(res)) break; //Registro exitoso mensaje recibido del Servidor
                System.out.println(">> Error: " + res);
            }

            // Una vez registrados, quitamos el timeout para que el hilo de escucha pueda esperar mensajes libremente
            socket.setSoTimeout(0);

            System.out.println("--- Conectado vía UDP ---");
            lanzarHilos(null, socket, direccionIP, puerto, null, "UDP");
            
            // Bucle de leectura en consola y envio de datos al servidor
            while (true) {
                String m = sc.nextLine();
                //Si el cliente escribe exit enviamos los datos de salida al servidor
                if (m.equalsIgnoreCase("exit")) {
                    byte[] b = (n + ":exit").getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(b, b.length, direccionIP, puerto));
                    break;
                }
                //Si solo escribe enviamos el mensaje, el servidor se encarga de revisar si es o no privado
                byte[] d = m.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(d, d.length, direccionIP, puerto));
            }
        } catch (Exception e) { 
            // Atrapa tanto errores de red como el SocketTimeoutException si el puerto es incorrecto
            System.out.println(">> Error: No se pudo conectar al servidor"); 
        }
        System.exit(0);
    }

    /**
     * Crea dos copias del flujo de ejecución.
     * 1. Uno para mandar "señales de vida" (Heartbeat).
     * 2. Otro para imprimir lo que otros usuarios escriben.
     */
    private static void lanzarHilos(PrintWriter out, DatagramSocket ds, InetAddress addr, int p, BufferedReader in, String proto) {
        
        // Hilo 1: Respiramos
        new Thread(() -> {
            try {
                while(true) { 
                    Thread.sleep(10000); //Cada 10 segundos
                    if (proto.equals("TCP"))
                        out.println("HEARTBEAT");//En TCP solo enviamos por el canal abierto
                    else {
                        byte[] b = "HEARTBEAT".getBytes();
                        // En UDP enviamos paquete por el DatagramSocket (datos, longitud, IP_destino, Puerto_destino))
                        ds.send(new DatagramPacket(b, b.length, addr, p));
                    }
                }
            } catch(Exception e){}
        }).start();

        // Hilo 2: Escuchamos
        new Thread(() -> {
            try {
                if (proto.equals("TCP")) {
                    String r;
                    // in.readLine() esperamos hasta que el servidor mande algo
                    while ((r = in.readLine()) != null) {//Cuando el servidor se desconecta devuelve null
                        System.out.println(r);//Imprimimos mensajes/
                    }
                } else {
                    byte[] buf = new byte[2048];
                    while (true) {
                        //Preparamos paquede de recepcion de datos
                        DatagramPacket pack = new DatagramPacket(buf, buf.length);
                        ds.receive(pack); // esperamos paquete del servidor
                        System.out.println(new String(pack.getData(), 0, pack.getLength(), StandardCharsets.UTF_8));//Formatemos e imprimimos mensajes
                    }
                }
            } catch (Exception e) { }
        }).start();
    }
}