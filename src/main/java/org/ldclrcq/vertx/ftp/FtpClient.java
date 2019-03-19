/*
 * Copyright 2016 Finn Bock
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ldclrcq.vertx.ftp;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main FtpClient class to use when sending commands to a FTP server.
 *
 * @see <a href="https://www.ietf.org/rfc/rfc959.txt">FTP RFC</a>
 */
public class FtpClient {
    private static Logger log = LoggerFactory.getLogger(FtpClient.class);

    private final Vertx vertx;
    private final String host;
    private final int port;
    private NetClient client;
    private NetSocket socket;
    private Handler<Void> endHandler = $ -> {
    };
    private Response response = null;
    private Handler<AsyncResult<Response>> next;

    /**
     * Creates a FTP client which connects to the specified host and port.
     *
     * @param vertx the Vert.x instance to use when creating connections.
     * @param host  the host where the FTP server is running.
     * @param port  the port where the FTP server is listening.
     */
    public FtpClient(Vertx vertx, String host, int port) {
        this.vertx = vertx;
        this.host = host;
        this.port = port;
    }

    /**
     * Connects to the FTP server.
     *
     * @param handler callback handler that is called when the connection is completed.
     */
    public void connect(Handler<AsyncResult<Void>> handler) {
        next = resp(handler, when("220", c -> handler.handle(Future.succeededFuture())));

        client = vertx.createNetClient();
        client.connect(port, host, res -> {
            socket = res.result();

            if (res.failed()) handler.handle(Future.failedFuture(res.cause()));
            else {
                RecordParser parser = RecordParser.newDelimited("\n", this::output);
                socket.handler(parser);
            }
        });
    }

    /**
     * Perform a login on the FTP server using the specified user name and password.
     *
     * @param user     the username
     * @param password the password
     * @param handler  callback handler that is called when the login is completed.
     */
    public void login(String user, String password, Handler<AsyncResult<Void>> handler) {
        write("USER " + user, resp(handler,
                when("230", u -> handler.handle(Future.succeededFuture())),
                when("331", "332", u -> write("PASS " + password, resp(handler,
                        when("230", "202", p -> handler.handle(Future.succeededFuture())))))));
    }

    /**
     * Perform a listing of files and directories in the current working directory.
     *
     * @param handler callback handler that is called when the list is completed.
     */
    public void list(Handler<AsyncResult<Buffer>> handler) {
        list(null, handler);
    }

    /**
     * Perform a listing of the files or directories in the specified directory
     *
     * @param path    the path to list.
     * @param handler callback handler that is called when the listing is completed.
     */
    public void list(String path, Handler<AsyncResult<Buffer>> handler) {
        Buffer data = Buffer.buffer();

        pasv(handler, datasocket -> datasocket.handler(data::appendBuffer), $ -> write("LIST" + (path != null ? " " + path : ""), resp(handler,
                when("125", "150", list -> handle(resp(handler,
                        when("226", "250", listdone -> handler.handle(Future.succeededFuture(data)))))))));
    }

    /**
     * Perform a retrieve (download) of the file at the specified path.
     *
     * @param path              the path of the file to retrieve
     * @param destinationBuffer a buffer where the retrieved file will be written
     * @param progress          a progress handler that is called for each part that is received
     * @param handler           callback handler that is called when the download is completed.
     */
    public void retr(String path, WriteStream<Buffer> destinationBuffer, Handler<Progress> progress, Handler<AsyncResult<Void>> handler) {
        AtomicInteger ends = new AtomicInteger(0);
        pasv(handler, datasocket -> {
            datasocket.endHandler($ -> {
                if (ends.incrementAndGet() == 2) {
                    handler.handle(Future.succeededFuture());
                }
            });
            new ProgressPump(datasocket, destinationBuffer, pumped -> progress.handle(new Progress(this, pumped))).start();
        }, $ -> write("RETR " + path, resp(handler,
                handlerTransferResult(handler, ends))));
    }

    /**
     * Uploads a file to the specified path
     *
     * @param path      the path to upload to.
     * @param localFile an asyncFile where the download file will be written
     * @param progress  a progress handler that is called for each part that is received.
     * @param handler   callback handler that is called when the list is completed.
     */
    public void stor(String path, ReadStream<Buffer> localFile, Handler<Progress> progress, Handler<AsyncResult<Void>> handler) {
        AtomicInteger ends = new AtomicInteger(0);
        pasv(handler, datasocket -> {
            localFile.endHandler($ -> {
                datasocket.close();
                if (ends.incrementAndGet() == 2) {
                    handler.handle(Future.succeededFuture());
                }
            });
            new ProgressPump(localFile, datasocket, pumped -> progress.handle(new Progress(this, pumped))).start();
        }, $ -> write("STOR " + path, resp(handler,
                handlerTransferResult(handler, ends))));
    }

    /**
     * Deletes a file at the specified path
     *
     * @param path    the path where to perform a delete
     * @param handler callback handler that is called when the delete is completed.
     */
    public void dele(String path, Handler<AsyncResult<Void>> handler) {
        write("DELE " + path, resp(handler,
                when("250", pasv -> handler.handle(Future.succeededFuture()))));
    }

    /**
     * Changes the current directory on the FTP server
     *
     * @param newDirectory the directory to change into.
     * @param handler      callback handler that is called when the directory change is completed.
     */
    public void cwd(String newDirectory, Handler<AsyncResult<Void>> handler) {
        write("CWD " + newDirectory, resp(handler,
                when("250", pasv -> handler.handle(Future.succeededFuture()))));
    }

    /**
     * Changes the current directory to the parent directory on the FTP server
     *
     * @param handler callback handler that is called when the directory change is completed.
     */
    public void cdup(Handler<AsyncResult<Void>> handler) {
        write("CDUP", resp(handler,
                when("200", pasv -> handler.handle(Future.succeededFuture()))));
    }

    /**
     * Deletes the specified directory
     *
     * @param directoryToDelete the path to the directory to delete
     * @param handler           callback handler that is called when the delete is completed
     */
    public void rmd(String directoryToDelete, Handler<AsyncResult<Void>> handler) {
        write("RMD " + directoryToDelete, resp(handler,
                when("250", pasv -> handler.handle(Future.succeededFuture()))));
    }

    /**
     * Starts the renaming scenario by telling the FTP server the path to be renamed
     * (must be followed by a rnto command)
     *
     * @param path    the path of the path to rename
     * @param handler callback handler that is called when the rename is completed
     */
    public void rnfr(String path, Handler<AsyncResult<Void>> handler) {
        write("RNFR " + path, resp(handler,
                when("350", rnfr -> handler.handle(Future.succeededFuture()))
        ));
    }

    /**
     * Ends the renaming scenario by telling the FTP server the new path
     * (must be preceded by a rnfr command)
     *
     * @param path
     * @param handler
     */
    public void rnto(String path, Handler<AsyncResult<Void>> handler) {
        write("RNTO " + path, resp(handler,
                when("250", rnto -> handler.handle(Future.succeededFuture()))
        ));
    }

    /**
     * Creates a new directory at the specified path
     *
     * @param newDirectoryPath the new directory path
     * @param handler          callback handler that is called when the directory creation is completed
     */
    public void mkd(String newDirectoryPath, Handler<AsyncResult<Void>> handler) {
        write("MKD " + newDirectoryPath, resp(handler,
                when("257", pasv -> handler.handle(Future.succeededFuture()))));
    }

    /**
     * Disconnects from the FTP server
     *
     * @param handler
     */
    public void quit(Handler<AsyncResult<Void>> handler) {
        write("QUIT", resp(handler,
                when("200", pasv -> handler.handle(Future.succeededFuture()))));
    }

    private void write(String cmd, Handler<AsyncResult<Response>> handler) {
        this.next = handler;
        write(cmd);
    }

    private void handle(Handler<AsyncResult<Response>> handler) {
        this.next = handler;
    }

    private void write(String line) {
        log.debug(">{}", line);
        socket.write(line + "\r\n");
    }

    private <T> void pasv(Handler<AsyncResult<T>> handler, Handler<NetSocket> dataConnection, Handler<Void> cmd) {
        write("PASV", resp(handler,
                when("227", pasv -> {
                    int port = pasvPort(pasv);
                    client.connect(port, host, res -> {
                        NetSocket datasocket = res.result();
                        dataConnection.handle(datasocket);
                    });
                    cmd.handle(null);
                })));
    }

    private void fail(String msg) {
        throw new RuntimeException(msg);
    }

    private void output(Buffer buf) {
        if (response == null) {
            response = new Response(next);
        }
        Pattern p = Pattern.compile("(\\d\\d\\d)([ -])(.*)");
        Matcher m = p.matcher(buf.toString().trim());

        if (m.matches()) {
            String code = m.group(1);
            log.debug(code + " " + m.group(2) + " " + m.group(3));
            if (code.equals("421")) {
                socket.close();
                endHandler.handle(null);
                return;
            }
            if (response == null) {
                fail("unexpected response " + buf);
                return;
            }
            response.setCode(m.group(1));
            response.addMessage(m.group(3));

            if (m.group(2).equals(" ")) {
                Response response = this.response;
                this.response = null;
                log.trace("handling {}", response);
                next.handle(Future.succeededFuture(response));
            } else if (m.group(2).equals("-")) {
                log.info("waiting for more");
            }
        }
    }

    private int pasvPort(Response resp) {
        Pattern p = Pattern.compile(".*\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\).*");
        Matcher m = p.matcher(resp.messages.get(0));
        if (m.matches()) {
            return Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        }
        throw new RuntimeException("xx");
    }

    private When handlerTransferResult(Handler<AsyncResult<Void>> handler, AtomicInteger ends) {
        return when("125", "150", retr -> handle(resp(handler,
                when("226", "250", listdone -> {
                    if (ends.incrementAndGet() == 2) {
                        handler.handle(Future.succeededFuture());
                    }
                }))));
    }

    public static <R> Handler<AsyncResult<Response>> resp(Handler<AsyncResult<R>> handler, When... whens) {
        return ar -> {
            if (ar.succeeded()) {
                Response r = ar.result();
                for (When w : whens) {
                    if (w.match(r)) {
                        return;
                    }
                }
                handler.handle(Future.failedFuture(r.code + " " + r.messages.get(0)));
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        };
    }

    public static When when(String code, Consumer<Response> func) {
        return new When(func, code);
    }

    public static When when(String code1, String code2, Consumer<Response> func) {
        return new When(func, code1, code2);
    }

    private static class When {
        String[] codes;
        Consumer<Response> func;

        public When(Consumer<Response> func, String... codes) {
            this.codes = codes;
            this.func = func;
        }

        public boolean match(Response r) {
            for (String code : codes) {
                if (r.getCode().equals(code)) {
                    func.accept(r);
                    return true;
                }
            }
            return false;
        }
    }
}
