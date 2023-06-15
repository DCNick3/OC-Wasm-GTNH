package ca.chead.ocwasm;

import ca.chead.ocwasm.state.FindEEPROM;
import ca.chead.ocwasm.state.State;
import ca.chead.ocwasm.state.Transition;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Future;
import li.cil.oc.api.Driver;
import li.cil.oc.api.driver.Item;
import li.cil.oc.api.driver.item.Memory;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * An OpenComputers architecture that runs WebAssembly binaries.
 */
@Architecture.Name("WebAssembly")
public final class CPU implements Architecture {
	/**
	 * The scaling factor for RAM.
	 *
	 * The RAM item capacities from the config file are multiplied by this
	 * number to translate to bytes of actual RAM granted to the Wasm module.
	 */
	private static final int RAM_SCALE = 1024;

	/**
	 * The machine in which the CPU is running.
	 */
	private final Machine machine;

	/**
	 * The current total amount of RAM installed in the computer.
	 *
	 * This field is written in {@link #recomputeMemory} on the server thread.
	 * Unlike most methods which are mutually exclusive across threads (i.e.
	 * only one method of a given architecture object can be called at a time),
	 * {@link #recomputeMemory} <em>can</em> be called concurrently with other
	 * methods; thus, this field is {@code volatile}.
	 */
	private volatile int installedRAM;

	/**
	 * The current execution state, or {@code null} if the machine is not in a
	 * runnable state.
	 */
	private State state;

	/**
	 * The future for the most recent submission of a snapshot for background
	 * writeout.
	 *
	 * Whenever {@link #save} is called, a snapshot is made and sent for
	 * writeout in the background. The future for that background writeout is
	 * saved here. If {@link #save} is called a second time before the snapshot
	 * is written out, the original writeout is cancelled, as there is no
	 * reason to write out a snapshot only to immediately overwrite it with
	 * another.
	 *
	 * This is {@code null} if no writeout has been requested yet.
	 */
	private Future<?> snapshotWriteoutFuture;

	/**
	 * Creates a new CPU.
	 *
	 * @param machine The machine in which the CPU is running.
	 */
	public CPU(final Machine machine) {
		super();
		this.machine = Objects.requireNonNull(machine);
		installedRAM = 0;
		state = null;
		snapshotWriteoutFuture = null;
	}

	/**
	 * Returns the amount of RAM installed in the computer.
	 *
	 * @return The amount of RAM, in bytes.
	 */
	public int getInstalledRAM() {
		return installedRAM;
	}

	@Override
	public boolean isInitialized() {
		return state != null && state.isInitialized();
	}

	@Override
	public boolean recomputeMemory(final Iterable<ItemStack> components) {
		int total = 0;
		for(final ItemStack i : components) {
			final Item driver = Driver.driverFor(i);
			if(driver instanceof Memory) {
				final Memory mem = (Memory) driver;
				total += mem.amount(i);
			}
		}
		installedRAM = total * RAM_SCALE;
		return total > 0;
	}

	@Override
	public boolean initialize() {
		close();
		state = new FindEEPROM(this, machine);
		return true;
	}

	@Override
	public void close() {
		if(state != null) {
			state.close();
		}
		state = null;
	}

	@Override
	public void runSynchronized() {
		state.runSynchronized();
	}

	@Override
	public ExecutionResult runThreaded(final boolean synchronizedReturn) {
		final Transition t = state.runThreaded();
		state = t.nextState;
		return t.executionResult;
	}

	@Override
	public void onSignal() {
		// This architecture only pulls signals at the start of a timeslice, so
		// there is nothing to do here.
	}

	@Override
	public void onConnect() {
		// This architecture doesn’t do anything in initialize, so there is
		// nothing to do here.
	}

	@Override
	public void load(final NBTTagCompound nbt) {
		close();
		state = State.load(this, machine, nbt);
	}

	@Override
	public void save(final NBTTagCompound nbt) {
		if(snapshotWriteoutFuture != null) {
			snapshotWriteoutFuture.cancel(false);
		}
		if(state != null) {
			final SnapshotOrGeneration snapshotResult = state.snapshot(nbt);
			snapshotResult.snapshot().ifPresent(snapshot -> {
				nbt.setLong(State.NBT_GENERATION, snapshot.generation);
				snapshotWriteoutFuture = OCWasm.submitBackground(() -> {
					try {
						snapshot.save();
					} catch(final IOException exp) {
						OCWasm.getLogger().error("I/O error saving computer snapshot", exp);
					}
				});
			});
		}
	}
}
