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

/**
 * Gestiona conexiones simultáneas de TCP y UDP.
 * Mantiene el estado global de los usuarios y distribuye los mensajes a todos los clientes.
 */
public class ServidorHibrido {
    private static final int MAX_CLIENTES = 5;
    private static final int TIMEOUT_MS = 20000; // 20 segundos de espera antes de desconectar por inactividad
    
    // Mapas Concurrentes: usamos Collections.synchronizedMap para que múltiples hilos 
    // puedan leer/escribir sin corromper los datos.
    private static Map<String, PrintWriter> mapaTCP = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, String> mapaUDP = Collections.synchronizedMap(new HashMap<>()); 
    private static Map<String, Long> ultimaActividad = Collections.synchronizedMap(new HashMap<>());
    private static DatagramSocket udpSocketGlobal;

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Puerto del servidor: ");
        int puerto = sc.nextInt();

        // Lanzamos tres hilos independientes para que el servidor no se detenga.
        new Thread(() -> iniciarTCP(puerto)).start(); // Escucha TCP
        new Thread(() -> iniciarUDP(puerto)).start(); // Escucha UDP
        new Thread(() -> hiloLimpiador()).start();    // Vigilamos inactividad

        System.out.println("Servidor iniciado en puerto " + puerto);
    }

    /**
     * Verifica la disponibilidad de un nickname en ambos protocolos para evitar duplicados.
     */
    private static boolean nombreOcupado(String nombre) {
        return mapaTCP.containsKey(nombre) || mapaUDP.containsValue(nombre);
    }

    /**
     * Recorre el registro de actividad. Si un cliente (TCP o UDP) no ha enviado 
     * ni un mensaje ni un latido (HEARTBEAT) en 20s, lo elimina de los mapas.
     */
    private static void hiloLimpiador() {
        while (true) {
            try {
                Thread.sleep(3000); // Revisamos cada 3 segundos para no saturar el CPU
                long ahora = System.currentTimeMillis();//Presente en una variable
                synchronized (ultimaActividad) {
                    Iterator<Map.Entry<String, Long>> it = ultimaActividad.entrySet().iterator();//Puntero para recorrer registros
                    while (it.hasNext()) {
                        Map.Entry<String, Long> registro = it.next();//Tomamos el registro
                        // (Tiempo actual - Última señal recibida) > 20 segundos
                        if (ahora - registro.getValue() > TIMEOUT_MS) {
                            String id = registro.getKey();
                            if (mapaTCP.containsKey(id)) {
                                System.out.println("Usuario [TCP] " + id + " desconectado");
                                mapaTCP.remove(id);
                            } else if (mapaUDP.containsKey(id)) {
                                System.out.println("Usuario [UDP] " + mapaUDP.get(id) + " desconectado");
                                mapaUDP.remove(id);
                            }
                            it.remove(); // Borramos el registro actual del mapa de tiempos
                        }
                    }
                }
            } catch (Exception e) { }
        }
    }

    /**
     * Abre un ServerSocket. Cada vez que accept() detecta una conexión, 
     * se crea un nuevo hilo dedicado exclusivamente a ese cliente.
     */
    public static void iniciarTCP(int puerto) {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            while (true) {
                // accept(): esperamos aquí hasta que un cliente se conecta
                Socket socket = serverSocket.accept(); 
                //iniciamos un nuevo hilo para el cliente ya que la comunicacion es continua en TCP
                new Thread(() -> manejarClienteTCP(socket)).start();
            }
        } catch (IOException e) { }
    }

    /**
     * Gestiona el flujo de entrada (lectura) y salida (escritura) de un cliente TCP. Hilo por cliente
     */
    private static void manejarClienteTCP(Socket socket) {
        String nombre = "";
        try (
             //Usamos el buffer para recibir informacion del cliente
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             //Cada vez que usemos out() el cliente TCP recibira la informacion por el printwriter
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            
            // Bucle de Validación: No dejamos pasar al cliente hasta que el nombre sea apto
            while (true) {
                nombre = in.readLine();
                if (nombre == null) return;
                // validación de nombre de usuario
                if (!nombre.matches("^[a-zA-Z0-9._]+$")) {
                    out.println("ERROR_FORMATO_INVALIDO"); // El cliente recibe aviso
                } else if (mapaTCP.size() + mapaUDP.size() >= MAX_CLIENTES) {
                    out.println("ERROR_SERVIDOR_LLENO");
                } else if (nombreOcupado(nombre)) {
                    out.println("ERROR_YA_EXISTE");
                } else {
                    out.println("OK"); // Autorizamos y aceptamos al cliente
                    break;
                }
            }

            mapaTCP.put(nombre, out);//Agregamos al cliente al registro
            ultimaActividad.put(nombre, System.currentTimeMillis());//Registramos actividad del cliente
            System.out.println("Usuario [TCP] " + nombre + " conectado.");

            String msg;
            while ((msg = in.readLine()) != null) {//Cada vez que tengamos mensajes del cliente
                ultimaActividad.put(nombre, System.currentTimeMillis()); // Registramos actividad
                if (msg.equalsIgnoreCase("exit")) break; //Sale y pasa por finally
                if (msg.equals("HEARTBEAT")) continue; // Ignora el latido en el chat público
                procesarMensaje(nombre, msg);
            }
        } catch (IOException e) { 
        } finally {
            // Nos aseguramos de liberar el nombre si el socket se rompe
            if (nombre != null && !nombre.isEmpty()) {
                mapaTCP.remove(nombre);
                ultimaActividad.remove(nombre);
                System.out.println("Usuario [TCP] " + nombre + " desconectado.");
            }
        }
    }

    /**
     * Escucha en el puerto mediante paquetes sueltos. Al no haber conexión persistente,
     * identifica a los usuarios por su IP y Puerto de origen.
     */
    public static void iniciarUDP(int puerto) {
        try {
            //Objeto que funciona como canal de comunicacion para UDP usamos el puerto seleccionado
            udpSocketGlobal = new DatagramSocket(puerto);
            byte[] buffer = new byte[1024]; // Espacio de memoria para guardar los paquetes es una bandeja de entrada
            while (true) {
                DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);//Le damos espacio en la memoria al Socket
                udpSocketGlobal.receive(paquete); // Se queda esperando un paquete
                
                // Genera un ID único basado en la dirección física del cliente
                String id = paquete.getAddress().getHostAddress() + ":" + paquete.getPort();// Formamos el ID único ("192.168.1.5:54321").
                String txt = new String(paquete.getData(), 0, paquete.getLength(), StandardCharsets.UTF_8).trim();//Cobvertimos con UT8 el mensaje leyendo desde la posicion 0

                //Cliente nuevo//
                if (txt.startsWith("JOIN:")) {
                    String n = txt.substring(5);//Depuramos el nombre//
                    byte[] resp;
                    // Validacion de nombre de usuario
                    if (!n.matches("^[a-zA-Z0-9._]+$")) {
                        resp = "ERROR_FORMATO_INVALIDO".getBytes();//Avisamos al cliente/
                    }else if (mapaTCP.size() + mapaUDP.size() >= MAX_CLIENTES) resp = "ERROR_LLENO".getBytes();
                    else if (nombreOcupado(n)) resp = "ERROR_YA_EXISTE".getBytes();
                    else {
                        mapaUDP.put(id, n);
                        ultimaActividad.put(id, System.currentTimeMillis());
                        resp = "OK".getBytes();
                        System.out.println("Usuario [UDP] " + n + " conectado.");
                    }
                    // Envíamos la respuesta (OK o ERROR) de vuelta al cliente
                    udpSocketGlobal.send(new DatagramPacket(resp, resp.length, paquete.getAddress(), paquete.getPort()));
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
                        procesarMensaje(n, txt);//Si llegamos a este punto el cliente escribio un mensaje
                    }
                }
            }
        } catch (IOException e) { }
    }

    /**
     * Analiza el texto para distinguir entre un comando privado (/priv) o 
     * un mensaje para todos. Le añade la fecha y formato final.
     */
    private static void procesarMensaje(String de, String m) {
        if (m.startsWith("/priv ")) {
            String resto = m.substring(6).trim(); //Cortamos el comando
            int primerEspacio = resto.indexOf(" ");//Guardamos posicion del primer espacio despues del cliente 
            if (primerEspacio != -1) { //verificamos si hay contenido
                String para = resto.substring(0, primerEspacio);//Guardamos destinatario
                String contenido = resto.substring(primerEspacio).trim();//Guardamos el mensaje
                enviarPrivado(de, para, contenido);//Enviamos privado
                return;
            }
        }
        
        // Formateamos el mensaje
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a"));
        String chat = "\n" + de + " " + fecha + ":\n" + m + "\n";
        
        System.out.println(chat);
        enviarATodos(chat);
    }

    /**
     * Busca al destinatario en ambos protocolos y le envía el mensaje solo a él.
     */
    private static void enviarPrivado(String de, String para, String texto) {
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a"));
        String chat = "\n[PRIVADO de " + de + "] " + fecha + ":\n" + texto + "\n";
        
        // TCP: Buscamos el PrintWriter del usuario
        PrintWriter out = mapaTCP.get(para);
        if (out != null) {
            out.println(chat);
            return;
        }

        // UDP: Buscamos el ID (IP:Puerto) para mandarle datagrama
        synchronized (mapaUDP) {
            for (Map.Entry<String, String> entry : mapaUDP.entrySet()) { //Iteramos el mapa de clientes UDP
                if (entry.getValue().equals(para)) { //Si encontramos al cliente le enviamos el mensaje//
                    try {
                        byte[] d = chat.getBytes(StandardCharsets.UTF_8);
                        String[] p = entry.getKey().split(":");//Colocamos IP y Puerto en un arreglo de strings
                        // Llamamos al metodo send de nuestro DatagramSocket(datos, longitud, IP, puerto)/
                        udpSocketGlobal.send(new DatagramPacket(d, d.length, InetAddress.getByName(p[0]), Integer.parseInt(p[1])));
                        return;
                    } catch (IOException e) { }
                }
            }
        }
    }

    /**
     * Recorre todos los usuarios de TCP y UDP y les reenvía el mensaje.
     */
    private static void enviarATodos(String chat) {
        synchronized(mapaTCP) {
            for (PrintWriter out : mapaTCP.values())//Recorremos y enviamos
                out.println(chat);           
        }
        synchronized(mapaUDP) {
            byte[] data = chat.getBytes(StandardCharsets.UTF_8); //Preparamos el mensaje/
            for (String id : mapaUDP.keySet()) {//Recorremos las keys
                try {
                    String[] p = id.split(":");//Separamos le Key en IP y puerto
                    udpSocketGlobal.send(new DatagramPacket(data, data.length, InetAddress.getByName(p[0]), Integer.parseInt(p[1])));
                } catch (IOException e) { }
            }
        }
    }
}
