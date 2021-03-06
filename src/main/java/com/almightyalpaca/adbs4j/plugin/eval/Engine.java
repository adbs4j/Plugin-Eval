package com.almightyalpaca.adbs4j.plugin.eval;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import org.jruby.embed.jsr223.JRubyEngineFactory;
import org.luaj.vm2.script.LuaScriptEngine;
import org.python.jsr223.PyScriptEngineFactory;

import com.google.common.util.concurrent.MoreExecutors;

import com.almightyalpaca.adbs4j.util.StringUtil;

import javaxtools.compiler.CharSequenceCompiler;
import javaxtools.compiler.CharSequenceCompilerException;

public enum Engine {

	JAVASCRIPT("JavaScript", "js", "javascript") {
		private final ScriptEngineManager engineManager = new ScriptEngineManager();

		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout, String script) {
			String importString = "";
			for (final String s : packageImports) {
				importString += s + ", ";
			}
			importString = StringUtil.replaceLast(importString, ", ", "");

			script = " (function() { with (new JavaImporter(" + importString + ")) {" + script + "} })();";
			return this.eval(fields, timeout, script, this.engineManager.getEngineByName("nashorn"));
		}
	},
	GROOVY("Groovy", "groovy") {
		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout,
				final String script) {
			String importString = "";
			for (final String s : classImports) {
				importString += "import " + s + ";";
			}
			for (final String s : packageImports) {
				importString += "import " + s + ".*;";
			}
			return this.eval(fields, timeout, importString + script, new GroovyScriptEngineImpl());
		}
	},
	RUBY("Ruby", "ruby", "jruby") {
		private final JRubyEngineFactory factory = new JRubyEngineFactory();

		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout,
				final String script) {
			return this.eval(fields, timeout, script, this.factory.getScriptEngine());
		}
	},
	PYTHON("Python", "python", "jython") {
		private final PyScriptEngineFactory factory = new PyScriptEngineFactory();

		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout,
				final String script) {
			String importString = "";
			for (final String s : classImports) {
				final String packageName = s.substring(0, s.lastIndexOf("."));
				final String className = s.substring(s.lastIndexOf("."));
				importString += "from " + packageName + " import " + className + "\n";
			}
			for (final String s : packageImports) {
				importString += "from " + s + " import *\n";
			}
			return this.eval(fields, timeout, importString + script, this.factory.getScriptEngine());
		}
	},
	LUA("Lua", "lua", "luaj") {
		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout,
				final String script) {
			return this.eval(fields, timeout, script, new LuaScriptEngine());
		}
	},
	JAVA("Java", "java") {

		private final String	begin			= "package eval;\n\n";
		private final String	beginClass		= "\npublic class EvalClass {\n\n    public EvalClass() {}\n";
		private final String	fields			= "\n    public java.io.PrintWriter out;\n    public java.io.PrintWriter err;\n";
		private final String	methods			= "\n    public <T> T print(T o) {\n        out.println(String.valueOf(o));\n        return o;\n    }\n\n    public <T> T printErr(T o) {\n        err.println(String.valueOf(o));\n        return o;\n    }\n";
		private final String	methodBegin		= "    public Object run() {\n";
		private final String	methodBeginVoid	= "    public void run() {\n";
		private final String	methodEnd		= "\n    }";
		private final String	endClass		= "\n}";

		public final Pattern	IMPORT_PATTERN	= Pattern.compile("(import\\s+[A-Za-z0-9_$.]+;)");

		@Override
		public Triple<Object, String, String> eval(final Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, final int timeout, String script) {
			final CharSequenceCompiler<Object> compiler = new CharSequenceCompiler<>(this.getClass().getClassLoader(), null);

			String importString = "";
			for (final String s : classImports) {
				importString += "import " + s + ";\n";
			}
			for (final String s : packageImports) {
				importString += "import " + s + ".*;\n";
			}

			script = " " + script;

			final Matcher matcher = this.IMPORT_PATTERN.matcher(script);
			while (matcher.find()) {
				final String s = matcher.group(1);
				script = script.replace(s, "");
				importString += matcher.group(1) + "\n";
			}

			String code = "";
			code += this.begin;
			code += importString;
			code += this.beginClass;
			code += this.fields;
			for (final Entry<String, Object> shortcut : fields.entrySet()) {
				code += "    public " + shortcut.getValue().getClass().getName() + " " + shortcut.getKey() + ";\n";
			}

			code += this.methods;
			if (script.contains("return ")) {
				code += this.methodBegin;
			} else {
				code += this.methodBeginVoid;
			}

			for (final String s : script.split("\n")) {
				code += "        " + s;
			}
			if (!script.trim().endsWith(";")) {
				code += ";";
			}
			code += this.methodEnd;
			code += this.endClass;

			final StringWriter outString = new StringWriter();
			final PrintWriter outWriter = new PrintWriter(outString);

			final StringWriter errorString = new StringWriter();
			final PrintWriter errorWriter = new PrintWriter(errorString);

			Object result = null;

			try {
				final Class<Object> clazz = compiler.compile("eval.EvalClass", code, null);
				final Object object = clazz.newInstance();
				for (final Entry<String, Object> shortcut : fields.entrySet()) {
					try {
						final Field field = clazz.getDeclaredField(shortcut.getKey());
						field.setAccessible(true);
						field.set(object, shortcut.getValue());
					} catch (NoSuchFieldException | SecurityException e) {
						e.printStackTrace(errorWriter);
					}
				}

				final Field fieldErrorWriter = clazz.getDeclaredField("err");
				fieldErrorWriter.setAccessible(true);
				fieldErrorWriter.set(object, errorWriter);

				final Field fieldOutWriter = clazz.getDeclaredField("out");
				fieldOutWriter.setAccessible(true);
				fieldOutWriter.set(object, outWriter);

				final Method method = clazz.getDeclaredMethod("run");
				method.setAccessible(true);

				final ScheduledFuture<Object> future = Engine.service.schedule(() -> {
					return method.invoke(object);
				}, 0, TimeUnit.MILLISECONDS);

				try {
					result = future.get(timeout, TimeUnit.SECONDS);
				} catch (final ExecutionException e) {
					errorWriter.println(e.getCause().toString());
				} catch (TimeoutException | InterruptedException e) {
					future.cancel(true);
					errorWriter.println(e.toString());
				}
			} catch (ClassCastException | CharSequenceCompilerException | InstantiationException | IllegalAccessException | SecurityException | IllegalArgumentException | NoSuchFieldException
					| NoSuchMethodException e) {
				e.printStackTrace(errorWriter);
			}
			return new ImmutableTriple<>(result, outString.toString(), errorString.toString());
		}
	};

	public static class Import {

		public static enum Type {
			CLASS,
			PACKAGE;
		}

		private final Type		type;
		private final String	name;

		public Import(final Import.Type type, final String name) {
			this.type = type;
			this.name = name;
		}

		public final String getName() {
			return this.name;
		}

		public final Type getType() {
			return this.type;
		}

	}

	private final static ScheduledExecutorService	service	= Executors.newScheduledThreadPool(1, r -> new Thread(r, "Eval-Thread"));

	private final List<String>						codes;

	private final String							name;

	private Engine(final String name, final String... codes) {
		this.name = name;
		this.codes = new ArrayList<>();
		for (final String code : codes) {
			this.codes.add(code.toLowerCase());
		}
	}

	public static Engine getEngineByCode(String code) {
		code = code.toLowerCase();
		for (final Engine engine : Engine.values()) {
			if (engine.codes.contains(code)) {
				return engine;
			}
		}
		return null;
	}

	public static void shutdown() {
		MoreExecutors.shutdownAndAwaitTermination(Engine.service, 10, TimeUnit.SECONDS);
	}

	public abstract Triple<Object, String, String> eval(Map<String, Object> fields, final Collection<String> classImports, final Collection<String> packageImports, int timeout, String script);

	protected Triple<Object, String, String> eval(final Map<String, Object> fields, final int timeout, final String script, final ScriptEngine engine) {

		for (final Entry<String, Object> shortcut : fields.entrySet()) {
			engine.put(shortcut.getKey(), shortcut.getValue());
		}

		final StringWriter outString = new StringWriter();
		final PrintWriter outWriter = new PrintWriter(outString);
		engine.getContext().setWriter(outWriter);

		final StringWriter errorString = new StringWriter();
		final PrintWriter errorWriter = new PrintWriter(errorString);
		engine.getContext().setErrorWriter(errorWriter);

		final ScheduledFuture<Object> future = Engine.service.schedule(() -> {
			return engine.eval(script);
		}, 0, TimeUnit.MILLISECONDS);

		Object result = null;

		try {
			result = future.get(timeout, TimeUnit.SECONDS);
		} catch (final ExecutionException e) {
			errorWriter.println(e.getCause().toString());
		} catch (TimeoutException | InterruptedException e) {
			future.cancel(true);
			errorWriter.println(e.toString());
		}

		return new ImmutableTriple<>(result, outString.toString(), errorString.toString());
	}

	public List<String> getCodes() {
		return this.codes;
	}

	public String getName() {
		return this.name;
	}
}
