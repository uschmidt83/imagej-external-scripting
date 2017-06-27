package org.uweschmidt.imagej;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.msgpack.MessagePack;
import org.msgpack.MessageTypeException;
import org.msgpack.template.Template;
import org.msgpack.template.Templates;
import org.scijava.command.Command;
import org.scijava.parse.ParseService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.ui.UIService;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import net.imagej.ImageJ;
import net.imagej.ops.OpService;

@Plugin(type = Command.class, menuPath = "Plugins > Scripting Server")
public class ScriptingServer implements Command {

	@Parameter
	private ImageJ ij;
	@Parameter
	private UIService uiService;
	@Parameter
	private OpService opService;
	@Parameter
	private ScriptService scriptService;
	@Parameter
	private ParseService parseService;

	private final Template<Map<String, String>> mapTemplate = Templates.tMap(Templates.TString, Templates.TString);
	private final MessagePack mp = new MessagePack();

	@Override
	public void run() {

		try (ZContext ctx = new ZContext()) {

			Socket server = ctx.createSocket(ZMQ.REP);
			server.bind("tcp://localhost:12345");

			while (true) {
				try {
					// wait for msg from client
					final Map<String, String> msg = mp.read(server.recv(), mapTemplate);
					// System.out.println(recv);
					final String scrName = msg.get("name");
					final String scrCode = msg.get("code");
					final String scrArgs = msg.get("args");
					final String scrHeadless = msg.get("headless");
					uiService.setHeadless(scrHeadless != null && Boolean.parseBoolean(scrHeadless));
					final Map<String, Object> scrArgsParsed = parseService.parse(scrArgs).asMap();
					// System.out.println(scrArgsParsed);
					final ScriptModule m = scriptService.run(scrName, scrCode, true, scrArgsParsed).get();
					// System.out.println(m.getOutputs());
					final Map<String, String> outputs = new HashMap<>();
					for (Entry<String, Object> entry : m.getOutputs().entrySet())
						outputs.put(entry.getKey(), String.valueOf(entry.getValue()));
					// send response to client
					serverSend(server, outputs);

				} catch (MessageTypeException e) {
					serverSend(server, "__exception__", "Message must be a like a Map<String, String>.", "stacktrace",
							ExceptionUtils.getStackTrace(e));
				} catch (Throwable e) {
					e.printStackTrace();
					serverSend(server, "__exception__", e.getMessage(), "stacktrace", ExceptionUtils.getStackTrace(e));
				}
			}
		}
	}

	private void serverSend(Socket server, Map<String, String> msg) {
		try {
			server.send(mp.write(msg, mapTemplate));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void serverSend(Socket server, String... strings) {
		serverSend(server, getMap(strings));
	}

	private Map<String, String> getMap(String... strings) {
		if (strings == null || strings.length == 0)
			return Collections.emptyMap();
		assert strings.length % 2 == 0;
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < strings.length; i += 2)
			map.put(strings[i], strings[i + 1]);
		return map;
	}

	public static void main(final String... args) throws Exception {
		final ImageJ ij = new ImageJ();
		ij.launch(args);
		ij.command().run(ScriptingServer.class, true);
	}

}
