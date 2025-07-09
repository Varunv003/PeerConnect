package peertoconnect.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import peertoconnect.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileController {

    private static final Logger logger = Logger.getLogger(FileController.class.getName());
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {

        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerconnect-uploads";;
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if(!uploadDirFile.exists()){
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());

        server.setExecutor(executorService);

    }

    public void start(){
        server.start();
        logger.info("API Server started on port: " + server.getAddress().getPort());
    }

    public void stop(){
        server.stop(0);
        executorService.shutdown();
        logger.info("API Server stopped.");
    }


    public static class CORSHandler implements HttpHandler{
        public void handle(HttpExchange exchange) throws IOException{

            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private class UploadHandler implements HttpHandler {

        final int MAX_UPLOAD_SIZE = 200 * 1024 * 1024; // 200 MB

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                logger.warning("Rejected non-POST request to /upload");
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");

            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                logger.warning("Upload failed: Content-Type is not multipart/form-data");
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            long startTime = System.currentTimeMillis();
            logger.info("Received file upload request.");
            try{
                String boundry = contentType.substring(contentType.indexOf("boundary=") + 9);

                InputStream inputStream = exchange.getRequestBody();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                byte[] buffer = new byte[4096];
                int bytesRead;
                int totalBytes = 0;
                while ((bytesRead = inputStream.read(buffer)) != -1) {

                    totalBytes+=bytesRead;

                    if (totalBytes > MAX_UPLOAD_SIZE) {
                        logger.warning("File too large");
                        String response = "File too large. Maximum allowed size is 200 MB.";
                        exchange.sendResponseHeaders(413, response.getBytes().length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        }
                        return;
                    }
                    baos.write(buffer, 0, bytesRead);
                }
                byte[] requestData = baos.toByteArray();

                MultipartParser parser = new MultipartParser(requestData, boundry);
                ParseResult result = parser.parse();

                if(result == null){
                    logger.warning("Upload failed: Could not parse multipart data.");
                    String response = "Bad Request: Could not parse file content";

                    exchange.sendResponseHeaders(400, response.getBytes().length);

                    try(OutputStream oos = exchange.getResponseBody()){
                        oos.write(response.getBytes());
                    }
                    return;
                }

                String fileName = result.filename;
                if(fileName == null || fileName.trim().isEmpty()){
                    fileName = "unnamed-file";
                }

                String uniqueFileName = UUID.randomUUID().toString()+"_"+new File(fileName).getName();
                String filePath = uploadDir + File.separator + uniqueFileName;

                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(result.fileContent);
                }

                int port = fileSharer.offerFile(filePath);

                new Thread(() -> fileSharer.startFileServer(port)).start();

                long endTime = System.currentTimeMillis();
                long fileSize = result.fileContent.length;

                logger.info("File uploaded successfully: " + uniqueFileName +
                        " | Size: " + fileSize + " bytes" +
                        " | Port: " + port +
                        " | Time taken: " + (endTime - startTime) + " ms");

                String jsonResponse = "{\"port\": " + port + "}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try(OutputStream oos = exchange.getResponseBody()){
                    oos.write(jsonResponse.getBytes());
                }
            }catch (Exception ex){
                logger.log(Level.SEVERE, "Upload failed with exception", ex);
                String response = "Server Error: " + ex.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);

                try(OutputStream oos = exchange.getResponseBody()){
                    oos.write(response.getBytes());
                }
            }
        }

    }

    private static class MultipartParser{

        private final byte[] data;
        private final String boundary;

        public MultipartParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }



        private ParseResult parse() throws IOException{

            try{
                //file name extraction
                String dataAsString = new String(data, StandardCharsets.ISO_8859_1);
                String fileNameMarker = "filename=\"";
                int fileNameStart = dataAsString.indexOf(fileNameMarker);
                if( fileNameStart == -1) {
                    logger.warning("No filename found in multipart data.");
                    throw new IOException("No filename found in the multipart data");
                }
                int fileNameEnd = dataAsString.indexOf("\"", fileNameStart + fileNameMarker.length());
                if(fileNameEnd == -1) {
                    logger.warning("Malformed filename found in multipart data.");
                    throw new IOException("Malformed filename in the multipart data");
                }
                String fileName = dataAsString.substring(fileNameStart + fileNameMarker.length(), fileNameEnd);
                logger.info("Parsed filename: " + fileName);

                //content type extraction
                String contentTypeMarker = "Content-Type: ";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, fileNameEnd);
                String contentType = "application/octet-stream";
                if( contentTypeStart == -1) {
                    logger.warning("No content-type found in multipart data. Using default.");
                    throw new IOException("No content-type found in the multipart data");
                }else{
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart + contentTypeMarker.length(), contentTypeEnd);
                    logger.info("Parsed content-type: " + contentType);
                }

                // Data extraction

                String headerEndMarker = "\r\n\r\n";

                int headerEnd = dataAsString.indexOf(headerEndMarker, contentTypeStart);
                if(headerEnd == -1){
                    logger.warning("Header end marker not found in multipart data.");
                    throw new IOException("No data found");
                }
                int contentStart = headerEnd + headerEndMarker.length();

                String endBoundry = "\r\n--" + boundary + "--";
                byte[] boundryBytes = endBoundry.getBytes();

                int contentEnd = ParseResult.findSequence(data, boundryBytes, contentStart);

                if(contentEnd == -1){
                    endBoundry = "\r\n--" + boundary;
                    boundryBytes = endBoundry.getBytes();
                    contentEnd = ParseResult.findSequence(data, boundryBytes, contentStart);
                }

                if(contentEnd == -1 || contentEnd <= contentStart){
                    logger.severe("Invalid content range: start=" + contentStart + ", end=" + contentEnd);
                    return null;
                }

                byte[] fileContent = new byte[contentEnd - contentStart];
                logger.info("Parsed file content. Size: " + fileContent.length + " bytes");

                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(fileName, contentType, fileContent);

            }catch (Exception ex){
                logger.log(Level.SEVERE, "Error parsing multipart data: " + ex.getMessage(), ex);
                return null;
            }

        }

    }

    private static class ParseResult {

        public final String filename;
        public final String contentType;
        public final byte[] fileContent;

        public ParseResult(
                String filename,
                String contentType,
                byte[] fileContent
        ) {
            this.filename = filename;
            this.contentType = contentType;
            this.fileContent = fileContent;
        }

        static int findSequence(byte[] data, byte[] sequence, int start){
            outer: for(int i = 0; i<=data.length - sequence.length; i++){

                for(int j = 0; j<sequence.length; j++){

                    if(data[i + j] != sequence[j])continue outer;
                }

                return i;
            }

            return -1;

        }
    }

    private static class DownloadHandler implements HttpHandler{

        @Override
        public void handle(HttpExchange exchange) throws IOException{

            logger.info("DownloadHandler triggered with URI: " + exchange.getRequestURI());

            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                logger.warning("Received non-GET request.");
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf('/') + 1);

            try{
                int port = Integer.parseInt(portStr);

                try(
                        Socket socket = new Socket("localhost", port);
                        InputStream socketInput = socket.getInputStream();

                ){
                    long downloadStartTime = System.currentTimeMillis();
                    logger.info("Socket connection established with localhost:" + port);
                    File tempFile = File.createTempFile("download-", ".tmp");
                    String fileName = "download-file";

                    try(FileOutputStream fos = new FileOutputStream(tempFile)){
                        byte[] buffer = new byte[4096];

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        int b;

                        while((b = socketInput.read()) != -1){
                            if(b == '\n')break;
                            baos.write(b);
                        }

                        String header = baos.toString().trim();

                        if(header.startsWith("Filename: ")){
                            fileName = header.substring("Filename: ".length());
                        }
                        int bytesRead;

                        while((bytesRead = socketInput.read(buffer)) != -1){
                            fos.write(buffer, 0, bytesRead);
                        }
                    }

                    long downloadEndTime = System.currentTimeMillis();
                    long duration = downloadEndTime - downloadStartTime;
                    logger.info("File downloaded from peer: " +
                            " | Size: " + tempFile.length() + " bytes" +
                            " | Time: " + duration + " ms");

                    headers.add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    headers.add("Content-Type", "application/octet-stream");

                    exchange.sendResponseHeaders(200, tempFile.length());
                    try(OutputStream oos = exchange.getResponseBody();
                        FileInputStream fis = new FileInputStream(tempFile);
                    ){


                        byte[] buffer = new byte[4096];

                        int byteRead;

                        while((byteRead = fis.read(buffer)) != -1){
                            oos.write(buffer, 0, byteRead);
                        }
                    }
                    tempFile.delete();
                    logger.info("Download completed!!");

                }catch (IOException e){

                    logger.log(Level.SEVERE, "Error downloading file from peer: " + e.getMessage(), e);
                    String response = "Error downloading file: " + e.getMessage();
                    headers.add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(500, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }

                }
            }catch (NumberFormatException e){
                logger.warning("Invalid port number in URI: " + portStr);
                String response = "Bad Request: Invalid Port Number";
                exchange.sendResponseHeaders(500, response.length());

                try(OutputStream oos = exchange.getResponseBody()){
                    oos.write(response.getBytes());
                }
            }

        }

    }
}
