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
            System.out.print("Puerto (1024-65535): ");
            try {
                puerto = Integer.parseInt(sc.nextLine());
                if (puerto >= 1024 && puerto <= 65535) {
                    break; 
                } else {
                    System.out.println(">> Error: El puerto debe estar entre 1024 y 65535.");
                }
            } catch (NumberFormatException e) {
                System.out.println(">> Error: Ingresa un número entero válido.");
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
            // Damos 3 segundos y evitamos que el programa se quede colgado si el puerto no responde
            socket.connect(new InetSocketAddress(ip, puerto), 3000); 
            socket.setSoTimeout(3000); 

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                
                String nombre;
                while (true) {
                    System.out.print("Nombre de usuario: ");
                    nombre = sc.nextLine().trim();
                    out.println(nombre); // Envíamos el nombre al servidor
                    String res = in.readLine(); // Esperamos validación: OK o ERROR
                    if ("OK".equals(res)) break; // Registro exitoso
                    System.out.println(">> Error: " + res);
                }

                // Una vez registrados, quitamos el timeout para que el hilo de escucha pueda esperar mensajes libremente
                socket.setSoTimeout(0);

                System.out.println("--- Conectado vía TCP ---");
                // Iniciamos los hilos
                lanzarHilos(out, null, null, 0, in, "TCP");

                // Bucle de lectura en consola (El "Jefe") y envio de datos al servidor
                while (true) {
                    String mensaje = sc.nextLine();
                    out.println(mensaje);
                    if (mensaje.equalsIgnoreCase("exit")) break;
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println(">> Error: No se pudo conectar al servidor (Tiempo agotado)");
        } catch (Exception e) { 
            System.out.println(">> Error: No se pudo conectar al servidor"); 
        } finally {
            System.exit(0);
        }
    }

    /**
     * Usa Datagramas para registrarse y enviar mensajes.
     */
    private static void iniciarUDP(String ip, int puerto, Scanner sc) {
        try (
            // Creamos el buzon de comunicacion
            DatagramSocket socket = new DatagramSocket()) {
            
            // Si el servidor no responde en 3 segundos, lanzamos error
            socket.setSoTimeout(3000); 
            
            InetAddress direccionIP = InetAddress.getByName(ip); // Preparamos y validamos la ip del servidor //
            String n;
            while (true) {
                System.out.print("Nombre de usuario: ");
                n = sc.nextLine().trim();
                byte[] j = ("JOIN:" + n).getBytes(StandardCharsets.UTF_8); // Preparamos datos de registro con el comando JOIN //
                // Envíamos paquete de registro al servidor
                socket.send(new DatagramPacket(j, j.length, direccionIP, puerto));
                
                // Preparamos donde guardar la respuesta del servidor (Bandeja de entrada)
                byte[] b = new byte[1024];
                DatagramPacket p = new DatagramPacket(b, b.length);
                socket.receive(p); // Esperamos y recibimos respuesta del servidor
                
                String res = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if ("OK".equals(res)) break; // Registro exitoso mensaje recibido del Servidor
                System.out.println(">> Error: " + res);
            }

            // Una vez registrados, quitamos el timeout para que el hilo de escucha pueda esperar mensajes libremente
            socket.setSoTimeout(0);

            System.out.println("--- Conectado vía UDP ---");
            lanzarHilos(null, socket, direccionIP, puerto, null, "UDP");
            
            // Bucle de lectura en consola (El "Jefe") y envio de datos al servidor
            while (true) {
                String m = sc.nextLine();
                // Si el cliente escribe exit enviamos los datos de salida al servidor
                if (m.equalsIgnoreCase("exit")) {
                    byte[] b = (n + ":exit").getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(b, b.length, direccionIP, puerto));
                    break;
                }
                // Si solo escribe enviamos el mensaje
                byte[] d = m.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(d, d.length, direccionIP, puerto));
            }
        } catch (SocketTimeoutException e) {
            System.out.println(">> Error: El servidor no respondió a tiempo. Revisa el puerto.");
        } catch (Exception e) { 
            System.out.println(">> Error: No se pudo conectar al servidor"); 
        } finally {
            System.exit(0);
        }
    }

    /**
     * Crea dos copias del flujo de ejecución (Hilos secundarios).
     * 1. Hilo 1: Respiramos (Manda Heartbeat).
     * 2. Hilo 2: Escuchamos (Recibe mensajes y detecta cierre).
     */
    private static void lanzarHilos(PrintWriter out, DatagramSocket ds, InetAddress addr, int p, BufferedReader in, String proto) {
        
        // Hilo 1: Respiramos (Frecuencia cardiaca del cliente)
        new Thread(() -> {
            try {
                while(true) { 
                    Thread.sleep(10000); // Cada 10 segundos
                    if (proto.equals("TCP"))
                        out.println("HEARTBEAT"); // En TCP solo enviamos por el canal abierto
                    else {
                        byte[] b = "HEARTBEAT".getBytes();
                        // En UDP enviamos paquete por el DatagramSocket (datos, longitud, IP_destino, Puerto_destino)
                        ds.send(new DatagramPacket(b, b.length, addr, p));
                    }
                }
            } catch(Exception e) {
                // El hilo muere silenciosamente si se pierde la conexión
            }
        }).start();

        // Hilo 2: Escuchamos (Detecta cierre del servidor)
        new Thread(() -> {
            try {
                if (proto.equals("TCP")) {
                    String r;
                    // in.readLine() esperamos hasta que el servidor mande algo
                    while ((r = in.readLine()) != null) { // Cuando el servidor se desconecta devuelve null
                        System.out.println(r); // Imprimimos mensajes /
                        // Autodetección de cierre del servidor por mensaje
                        if (r.contains("SERVIDOR CERRÁNDOSE")) {
                            System.out.println("\n>> Saliendo del sistema...");
                            System.exit(0);
                        }
                    }
                } else {
                    byte[] buf = new byte[2048];
                    while (true) {
                        // Preparamos paquete de recepcion de datos
                        DatagramPacket pack = new DatagramPacket(buf, buf.length);
                        ds.receive(pack); // esperamos paquete del servidor
                        String msg = new String(pack.getData(), 0, pack.getLength(), StandardCharsets.UTF_8);
                        System.out.println(msg); // Formatemos e imprimimos mensajes
                        // Autodetección de cierre para UDP
                        if (msg.contains("SERVIDOR CERRÁNDOSE")) {
                            System.out.println("\n>> Saliendo del sistema...");
                            System.exit(0);
                        }
                    }
                }
            } catch (Exception e) { 
                System.out.println("\n>> Conexión terminada.");
                System.exit(0);
            }
        }).start();
    }
}