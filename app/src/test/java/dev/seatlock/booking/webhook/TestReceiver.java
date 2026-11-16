package dev.seatlock.booking.webhook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A local webhook receiver: answers with scripted responses (default 200) and records every request. */
class TestReceiver implements AutoCloseable {

	record Response(int status, String body, Duration delay) {
	}

	record Request(Headers headers, String body) {

		String header(String name) {
			return headers.getFirst(name);
		}

	}

	private final Queue<Response> script = new ConcurrentLinkedQueue<>();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final HttpServer server;

	TestReceiver() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/hook", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	void respond(int status) {
		respond(status, null, Duration.ZERO);
	}

	void respond(int status, String body, Duration delay) {
		script.add(new Response(status, body, delay));
	}

	List<Request> requests() {
		return requests;
	}

	String url() {
		return "http://localhost:" + port() + "/hook";
	}

	int port() {
		return server.getAddress().getPort();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			requests.add(new Request(exchange.getRequestHeaders(),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Response response = script.poll();
			if (response == null) {
				response = new Response(200, null, Duration.ZERO);
			}
			Thread.sleep(response.delay());
			if (response.status() / 100 == 3) {
				exchange.getResponseHeaders().add("Location", url());
			}
			if (response.body() == null) {
				exchange.sendResponseHeaders(response.status(), -1);
				return;
			}
			byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

}
