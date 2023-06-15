package ca.chead.ocwasm;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Logger;

/**
 * The mod entry point.
 */
@Mod(modid = OCWasm.MODID, name = OCWasm.NAME, version = OCWasm.VERSION, useMetadata = true, acceptableRemoteVersions = "*")
//@Mod.EventBusSubscriber
// OCWasm is instantiated by FML; it is not an uninstantiated utility class.
// However, CPU is instantiated by OpenComputers and isn’t passed any
// constructor parameters, so it can’t get hold of a reference to the OCWasm
// instance, so all the fields and methods in this class that anyone else uses
// need to be static. FML promises to only create one instance, so this is
// fine.
//
// Lots of fields used by other classes really ought to be public final, except
// they can’t be, partly because of the above problem requiring them to be
// static, and partly because FML does multi-phase initialization rather than
// expecting you to initialize in your constructor.
@SuppressWarnings({"checkstyle:HideUtilityClassConstructor", "checkstyle:VisibilityModifier"})
public final class OCWasm {
	/**
	 * Configuration for the mod.
	 */
//	@Config(modid = MODID)
	public static class OCWasmConfig {
		/**
		 * Whether the {@link ca.chead.ocwasm.syscall.Computer#debug} method
		 * writes to the Minecraft log.
		 */
//		@Config.Comment({"Whether the computer.debug syscall writes to the Minecraft debug log (otherwise, debug output is discarded)."})
//		@Config.Name("Enable Debug")
		public static boolean enableDebug = false;

		/**
		 * Whether a failed syscall writes an exception stack trace to the
		 * Minecraft log.
		 */
//		@Config.Comment({"Whether (most) failed syscalls write exception stack traces to the Minecraft debug log."})
//		@Config.Name("Enable Syscall Exception Trace Dump")
		public static boolean enableSyscallExceptionTraceDump = false;
	}

	/**
	 * The mod ID.
	 */
	public static final String MODID = "ca.chead.oc-wasm";

	/**
	 * The human-readable name.
	 */
	public static final String NAME = "OC-Wasm";

	/**
	 * The mod version.
	 */
	public static final String VERSION = "1.12.2-0.5.0";

	/**
	 * The number of milliseconds per second.
	 */
	private static final double MILLISECONDS_PER_SECOND = 1000.0;

	/**
	 * The number of seconds to wait for the background executor to terminate
	 * when the server is shutting down, before aborting.
	 */
	private static final int BACKGROUND_EXECUTOR_TERMINATION_TIME_LIMIT = 300;

	/**
	 * The logger used globally for this mod.
	 */
	private static Logger logger;

	/**
	 * The CPU timeout value, in milliseconds.
	 */
	private static long timeout;

	/**
	 * The priority of worker threads.
	 */
	private static int workerPriority;

	/**
	 * The method handle to {@code li.cil.oc.util.FontUtils.wcwidth}.
	 */
	private static MethodHandle wcwidthHandle;

	/**
	 * The method handle to {@code li.cil.oc.server.driver.Registry.convert}.
	 */
	private static MethodHandle registryConvertHandle;

	/**
	 * An executor for doing general background tasks.
	 *
	 * This object only exists in between server-start and server-stop.
	 */
	private static ScheduledThreadPoolExecutor backgroundExecutor;

	/**
	 * A zero-length array of {@code Object}.
	 *
	 * This is used in a few places to avoid unnecessary allocations.
	 */
	public static final Object[] ZERO_OBJECTS = new Object[0];

	/**
	 * The size of a page, in bytes.
	 */
	public static final int PAGE_SIZE = 65536;

	/**
	 * Constructs the mod instance.
	 */
	public OCWasm() {
		super();

        MinecraftForge.EVENT_BUS.register(this);
	}

	/**
	 * Returns the logger for this mod.
	 *
	 * @return The logger.
	 */
	public static Logger getLogger() {
		return Objects.requireNonNull(logger);
	}

	/**
	 * Returns the timeslice length, in milliseconds.
	 *
	 * @return The timeslice.
	 */
	public static long getTimeout() {
		if(timeout == 0) {
			throw new IllegalStateException("Mod not initialized yet");
		}
		return timeout;
	}

	/**
	 * Submits a task for immediate execution on the background executor.
	 *
	 * @param task The task to execute.
	 * @return A future for the task’s progress and result.
	 */
	public static Future<?> submitBackground(final Runnable task) {
		return backgroundExecutor.submit(task);
	}

	/**
	 * Submits a task for execution on the background executor at a later time.
	 *
	 * @param task The task to execute.
	 * @param delay How long to wait before executing the task.
	 * @param unit The unit of measurement for {@code delay}.
	 * @return A future for the task’s progress and result.
	 */
	public static ScheduledFuture<?> scheduleBackground(final Runnable task, final long delay, final TimeUnit unit) {
		return backgroundExecutor.schedule(task, delay, unit);
	}

	/**
	 * Converts an array of values from their internal Java representations, as
	 * returned by a component or driver, to representations suitable for
	 * exposing to user code.
	 *
	 * @param values The values to convert.
	 * @return The converted values.
	 */
	public static Object[] convertValues(final Object[] values) {
		try {
			return (Object[]) registryConvertHandle.invoke(values);
		} catch(final RuntimeException exp) {
			throw exp;
		} catch(final Error exp) {
			throw exp;
		} catch(final Throwable exp) {
			// The convert method is not declared as throwing any checked
			// exceptions.
			throw new RuntimeException("Impossible exception", exp);
		}
	}

    static void reloadConfig(File file) {
    }

	/**
	 * Handles the FML pre-initialization event.
	 *
	 * @param event The event.
	 */
	@Mod.EventHandler
	public static void preInit(final FMLPreInitializationEvent event) {
		logger = event.getModLog();
		SyntheticFuncBuilderPatcher.patch();

        Configuration config = new Configuration(event.getSuggestedConfigurationFile());

        config.load();

        OCWasmConfig.enableDebug = config.getBoolean("enableDebug", Configuration.CATEGORY_GENERAL, OCWasmConfig.enableDebug, "Whether the computer.debug syscall writes to the Minecraft debug log (otherwise, debug output is discarded).");
        OCWasmConfig.enableSyscallExceptionTraceDump = config.getBoolean("enableSyscallExceptionTraceDump", Configuration.CATEGORY_GENERAL, OCWasmConfig.enableSyscallExceptionTraceDump, "Whether (most) failed syscalls write exception stack traces to the Minecraft debug log.");

        config.save();
	}

	/**
	 * Handles the FML post-initialization event.
	 *
	 * @param event The event.
	 */
	@Mod.EventHandler
	public static void postInit(final FMLPostInitializationEvent event) {
		// Register the architecture.
		li.cil.oc.api.Machine.add(CPU.class);

		// Fetch settings from OpenComputers that we care about.
		timeout = Math.round((Double) getOCSetting("timeout") * MILLISECONDS_PER_SECOND);
		workerPriority = decodeThreadPrioritySetting((Integer) getOCSetting("threadPriority"));

		// Obtain a method handle to li.cil.oc.util.FontUtils.wcwidth, which is
		// not included in the API but which we need in order to expose the
		// character width API to user code.
		wcwidthHandle = getMethodHandle("li.cil.oc.util.FontUtils", "wcwidth", int.class);

		// Obtain a method handle to li.cil.oc.server.driver.Registry.convert,
		// which is not included in the API but which we need to convert
		// internal object types to user-visible types (which is normally done
		// automatically by Machine.invoke, except that that method cannot be
		// used to invoke a callable opaque value).
		registryConvertHandle = getMethodHandle("li.cil.oc.server.driver.Registry", "convert", Object[].class);
	}

	/**
	 * Handles the FML server-starting event.
	 *
	 * @param event The event.
	 */
	@Mod.EventHandler
	public static void serverStarting(final FMLServerStartingEvent event) {
		final ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
			@Override
			public Thread newThread(final Runnable r) {
				final Thread t = new Thread(r, "oc-wasm-background");
				t.setPriority(workerPriority);
				t.setDaemon(true);
				return t;
			}
		});
		exec.setRemoveOnCancelPolicy(true);
		backgroundExecutor = exec;
	}

	/**
	 * Handles the FML server-stopped event.
	 *
	 * @param event The event.
	 */
	@Mod.EventHandler
	public static void serverStopped(final FMLServerStoppedEvent event) {
		backgroundExecutor.shutdown();
		try {
			backgroundExecutor.awaitTermination(BACKGROUND_EXECUTOR_TERMINATION_TIME_LIMIT, TimeUnit.SECONDS);
		} catch(final InterruptedException exp) {
			// Normally we want to join the writeout thread to ensure that all
			// snapshots are written out before the process terminates. If the
			// user really wants to kill things fast, just return without
			// joining; the thread is a dæmon so it will not prevent process
			// termination. That will likely result in incomplete snapshots,
			// but that’s what the user asked for.
		}
		backgroundExecutor = null;
	}

	/**
	 * Handles the Forge configuration changed event.
	 *
	 * @param event The event.
	 */
	@SubscribeEvent
	public static void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event) {
		// похуй
	}

	/**
	 * Fetches the value of an OpenComputers setting.
	 *
	 * @param name The name of the setting.
	 * @return The setting value.
	 */
	private static Object getOCSetting(final String name) {
		// Unfortunately the Settings class is not part of the API. Reflection
		// is ugly, but re-parsing the config file would be even uglier, so
		// this is the lesser of two evils.
		try {
			final Class<?> settingsClass = Class.forName("li.cil.oc.Settings");
			final Method getMethod = settingsClass.getMethod("get");
			final Object settingsObject = getMethod.invoke(null);
			final Method timeoutMethod = settingsClass.getMethod(name);
			return timeoutMethod.invoke(settingsObject);
		} catch(final ReflectiveOperationException exp) {
			throw new RuntimeException("Error fetching OpenComputers " + name + " setting", exp);
		}
	}

	/**
	 * Fetches a method handle to a method.
	 *
	 * @param className The name of the class containing the method.
	 * @param method The name of the method.
	 * @param paramTypes The types of the parameters.
	 * @return The method handle.
	 */
	private static MethodHandle getMethodHandle(final String className, final String method, final Class<?>... paramTypes) {
		try {
			final Class<?> clazz = Class.forName(className);
			final Method m = clazz.getDeclaredMethod(method, paramTypes);
			return MethodHandles.lookup().unreflect(m);
		} catch(final ReflectiveOperationException exp) {
			throw new RuntimeException("Error obtaining handle to OpenComputers " + className + "." + method + " method");
		}
	}

	/**
	 * Returns the width of a Unicode character.
	 *
	 * @param c The character, as a Unicode code point.
	 * @return The width, as a count of terminal columns.
	 */
	public static int getCharacterWidth(final int c) {
		try {
			return (Integer) wcwidthHandle.invoke(c);
		} catch(final Error e) {
			throw e;
		} catch(final RuntimeException e) {
			throw e;
		} catch(final Throwable t) {
			// wcwidth should never throw anything.
			throw new RuntimeException(t);
		}
	}

	/**
	 * Converts the value of the thread priority setting in the configuration
	 * file into an actual, legitimate thread priority.
	 *
	 * @param value The setting value.
	 * @return The thread priority.
	 */
	private static int decodeThreadPrioritySetting(final int value) {
		if(value < 1) {
			// Nonpositive means unspecified, so use half way between minimum
			// and normal.
			return (Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2;
		} else {
			// Positive means an actual value, but clamp it.
			return Math.min(Thread.MAX_PRIORITY, Math.max(Thread.MIN_PRIORITY, value));
		}
	}
}
