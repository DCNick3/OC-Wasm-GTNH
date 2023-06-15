package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.Compiler;
import ca.chead.ocwasm.ExceptionTranslator;
import ca.chead.ocwasm.Instantiator;
import ca.chead.ocwasm.ModuleBase;
import ca.chead.ocwasm.ModuleConstructionListener;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.SnapshotOrGeneration;
import ca.chead.ocwasm.syscall.Syscalls;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The state the CPU is in when it needs to instantiate a compiled Wasm module
 * from a snapshot.
 */
public final class InstantiateFromSnapshot extends State implements ModuleConstructionListener {
	/**
	 * The compiler output.
	 */
	private final Compiler.Result compileResult;

	/**
	 * The snapshot.
	 */
	private final Snapshot snapshot;

	/**
	 * The machine root NBT compound.
	 */
	private final NBTTagCompound root;

	/**
	 * Constructs a new {@code InstantiateFromSnapshot}.
	 *
	 * The snapshot must contain globals and memory.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param compileResult The compiler output.
	 * @param snapshot The snapshot.
	 * @param root The machine root NBT compound to restore.
	 */
	public InstantiateFromSnapshot(final CPU cpu, final Machine machine, final Compiler.Result compileResult, final Snapshot snapshot, final NBTTagCompound root) {
		super(cpu, machine);
		if(!snapshot.globals.isPresent() || !snapshot.memory.isPresent()) {
			throw new IllegalArgumentException("Snapshot is missing globals or memory");
		}
		this.compileResult = Objects.requireNonNull(compileResult);
		this.snapshot = Objects.requireNonNull(snapshot);
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
		// Create the memory. The (int) cast is safe because, while the second
		// parameter could be larger than Integer.MAX_VALUE, it will be clamped
		// by the first (cpu.getInstalledRAM()) which cannot be.
		final int maxMemSize = (int) Math.min(cpu.getInstalledRAM(), compileResult.maxLinearMemory.orElse(Integer.MAX_VALUE) * (long) OCWasm.PAGE_SIZE);
		final long initialMemSize = compileResult.initialLinearMemory * (long) OCWasm.PAGE_SIZE;
		if(maxMemSize < initialMemSize) {
			return new Transition(null, new ExecutionResult.Error("Insufficient memory: " + maxMemSize + " bytes available but Wasm module requires " + initialMemSize + " to boot"));
		}
		final ByteBuffer memory = ByteBuffer.allocate(maxMemSize);

		// Instantiate the syscalls.
		final Syscalls syscalls = new Syscalls(machine, cpu, memory, root, snapshot);

		// Instantiate the module, passing the global data from the snapshot.
		final ModuleBase instance;
		try {
			instance = Instantiator.instantiate(compileResult.clazz, memory, syscalls.modules, this, Optional.of(ByteBuffer.wrap(snapshot.globals.get())));
		} catch(final Throwable t) {
			syscalls.close();
			return new Transition(null, ExceptionTranslator.translate(t));
		}

		// Now that the module has been instantiated, which includes setting
		// the memory byte order and limit and loading data segments into it,
		// replace the memory limit and contents with the values from the
		// snapshot.
		final byte[] memoryData = snapshot.memory.get();
		memory.limit(memoryData.length);
		memory.put(memoryData);

		return new Transition(new Run(cpu, machine, compileResult, memory, instance, syscalls), SLEEP_ZERO);
	}

	@Override
	public void instanceConstructed(final ModuleBase instance) {
		// When restoring a snapshot, the start function does not run, so we
		// don’t need to do anything here.
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound saveRoot) {
		// We do have a snapshot which we could provide, but there’s no need.
		// We haven’t mutated the computer’s state yet, so the snapshot that we
		// just loaded from disk is still accurate. Reuse it. However we do
		// need to copy the existing NBT data from the old root to the new
		// root.
        @SuppressWarnings("unchecked") // it's just missing from the mappings
        Set<String> keySet = root.func_150296_c();

		keySet.stream().filter(i -> i.startsWith("ca.chead.ocwasm.")).forEach(i -> saveRoot.setTag(i, root.getTag(i)));
		return new SnapshotOrGeneration(snapshot.generation);
	}

	@Override
	public void close() {
	}
}
