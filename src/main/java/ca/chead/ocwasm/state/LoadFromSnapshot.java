package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.SnapshotOrGeneration;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The state a CPU is in when it needs to load saved snapshot data from disk.
 */
public final class LoadFromSnapshot extends State {
	/**
	 * The machine root NBT compound.
	 */
	private final NBTTagCompound root;

	/**
	 * Constructs a new {@code LoadFromSnapshot} state.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param root The same machine root NBT compound to restore that was
	 * previously passed to {@link #snapshot}.
	 */
	public LoadFromSnapshot(final CPU cpu, final Machine machine, final NBTTagCompound root) {
		super(cpu, machine);
		this.root = Objects.requireNonNull(root);
	}

	@Override
	public boolean isInitialized() {
		// We don’t want to lose any events, to preserve the illusion of
		// continuous execution.
		return true;
	}

	@Override
	public Transition runThreaded() {
		final Snapshot snapshot;
		try {
			snapshot = Snapshot.load(machine.node().address());
		} catch(final IOException exp) {
			OCWasm.getLogger().error("I/O error loading snapshot for computer {}", machine.node().address(), exp);
			return new Transition(null, new ExecutionResult.Error("I/O error loading snapshot"));
		}
		final long nbtGeneration = root.getLong(NBT_GENERATION);
		if(snapshot.generation != nbtGeneration) {
			OCWasm.getLogger().error("Generation mismatch for computer {}, NBT has {}, snapshot has {}", machine.node().address(), nbtGeneration, snapshot.generation);
			return new Transition(null, new ExecutionResult.Error("Generation mismatch"));
		}
		final byte[] binary = snapshot.binary.orElse(null);
		final State nextState;
		if(binary != null) {
			nextState = new Compile(cpu, machine, binary, Optional.of(snapshot), Optional.of(root));
		} else {
			nextState = new FindEEPROM(cpu, machine);
		}
		return new Transition(nextState, SLEEP_ZERO);
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound saveRoot) {
		// We haven’t even loaded the snapshot data from disk yet, so we don’t
		// have it lying around in memory to write back. However, we also
		// haven’t changed the computer state at all since being asked to start
		// loading, so we can just reuse the existing snapshot. However we do
		// need to copy the existing NBT data from the old root to the new
		// root.
        @SuppressWarnings("unchecked") // it's just missing from the mappings
        Set<String> keySet = root.func_150296_c();

		keySet.stream().filter(i -> i.startsWith("ca.chead.ocwasm.")).forEach(i -> saveRoot.setTag(i, root.getTag(i)));
		return new SnapshotOrGeneration(root.getLong(NBT_GENERATION));
	}

	@Override
	public void close() {
	}
}
