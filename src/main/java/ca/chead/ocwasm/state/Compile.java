package ca.chead.ocwasm.state;

import ca.chead.ocwasm.BadWasmException;
import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.Compiler;
import ca.chead.ocwasm.PostprocessException;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.SnapshotOrGeneration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The state the CPU is in when it needs to compile a Wasm binary into a Java
 * class.
 */
public final class Compile extends State {
	/**
	 * The Wasm binary.
	 */
	private final byte[] binary;

	/**
	 * The snapshot to restore, or {@code null} if there is no snapshot.
	 */
	private final Snapshot snapshot;

	/**
	 * The machine root NBT compound.
	 */
	private final NBTTagCompound root;

	/**
	 * Constructs a new Compile state for a load of a saved machine state.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param binary The Wasm binary to compile.
	 * @param snapshot The snapshot to restore, if any.
	 * @param root The machine root NBT compound to restore, if any.
	 */
	public Compile(final CPU cpu, final Machine machine, final byte[] binary, final Optional<Snapshot> snapshot, final Optional<NBTTagCompound> root) {
		super(cpu, machine);
		this.binary = Objects.requireNonNull(binary);
		if(snapshot.isPresent() != root.isPresent()) {
			throw new IllegalArgumentException("Snapshot must be present if and only if NBT is present");
		}
		this.snapshot = snapshot.orElse(null);
		this.root = root.orElse(null);
	}

	@Override
	public boolean isInitialized() {
		// If we’re loading a snapshot, we don’t want to lose any events, to
		// preserve the illusion of continuous execution. If we’re not loading
		// a snapshot, events at this stage are redundant as the Wasm module
		// should be expected to enumerate components on first run if it cares.
		// We don’t care about the impact on direct call budget because we
		// aren’t running any Wasm code in this state.
		return snapshot != null;
	}

	@Override
	public Transition runThreaded() {
		final Compiler.Result result;
		try {
			result = Compiler.compile(binary);
		} catch(final BadWasmException exp) {
			return new Transition(null, new ExecutionResult.Error(exp.getMessage()));
		} catch(final PostprocessException exp) {
			return new Transition(null, new ExecutionResult.Error(exp.getMessage()));
		}
		final boolean hasGlobals = snapshot != null ? snapshot.globals.isPresent() : false;
		if(hasGlobals) {
			return new Transition(new InstantiateFromSnapshot(cpu, machine, result, snapshot, root), SLEEP_ZERO);
		} else {
			return new Transition(new InstantiateClean(cpu, machine, result), SLEEP_ZERO);
		}
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound saveRoot) {
		if(snapshot != null) {
			// We do have a snapshot which we could provide, but there’s no
			// need. We haven’t mutated the computer’s state yet, so the
			// snapshot that we just loaded from disk is still accurate. Reuse
			// it. However we do need to copy the existing NBT data from the
			// old root to the new root.
			@SuppressWarnings("unchecked") // it's just missing from the mappings
            Set<String> keySet = root.func_150296_c();

            keySet.stream().filter(i -> i.startsWith("ca.chead.ocwasm.")).forEach(i -> saveRoot.setTag(i, root.getTag(i)));
			return new SnapshotOrGeneration(snapshot.generation);
		} else {
			// This is a fresh boot, so we can get away with not saving
			// anything at all.
			return new SnapshotOrGeneration(new Snapshot(machine.node().address(), OptionalLong.empty(), Optional.of(binary), Optional.empty(), Optional.empty(), Optional.empty()));
		}
	}

	@Override
	public void close() {
	}
}
