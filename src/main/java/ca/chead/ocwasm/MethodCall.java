package ca.chead.ocwasm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Value;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/**
 * All the information needed to call a method on a component or value.
 */
public abstract class MethodCall implements AutoCloseable {
	/**
	 * An implementation of {@code li.cil.oc.api.machine.Arguments} that wraps
	 * an array of {@code Object}.
	 */
	private static final class Arguments implements li.cil.oc.api.machine.Arguments {
		/**
		 * The parameter list.
		 */
		private final Object[] parameters;

		/**
		 * Constructs a new {@code Arguments}.
		 *
		 * @param parameters The parameter list to wrap.
		 */
		Arguments(final Object[] parameters) {
			super();
			this.parameters = Objects.requireNonNull(parameters);
		}

		/**
		 * Checks whether an index is in range.
		 *
		 * @param index The index.
		 * @return {@code true} if {@code index} is in range, or {@code false}
		 * if not.
		 */
		private boolean inRange(final int index) {
			return 0 <= index && index < parameters.length;
		}

		@Override
		public Iterator<Object> iterator() {
			return Arrays.stream(parameters).iterator();
		}

		@Override
		public int count() {
			return parameters.length;
		}

		@Override
		public Object checkAny(final int index) {
			if(inRange(index)) {
				return parameters[index];
			} else {
				throw new IllegalArgumentException("Index out of range");
			}
		}

		@Override
		public boolean checkBoolean(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Boolean) {
				return (Boolean) o;
			} else {
				throw new IllegalArgumentException("Expected a boolean");
			}
		}

		@Override
		public int checkInteger(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Integer) {
				return (Integer) o;
			} else {
				throw new IllegalArgumentException("Expected an integer");
			}
		}

		@Override
		public double checkDouble(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Double) {
				return (Double) o;
			} else {
				throw new IllegalArgumentException("Expected a double");
			}
		}

		@Override
		public String checkString(final int index) {
			final Object o = checkAny(index);
			if(o instanceof String) {
				return (String) o;
			} else {
				throw new IllegalArgumentException("Expected a string");
			}
		}

		@Override
		public byte[] checkByteArray(final int index) {
			final Object o = checkAny(index);
			if(o instanceof byte[]) {
				return (byte[]) o;
			} else {
				throw new IllegalArgumentException("Expected a byte array");
			}
		}

		@Override
		public Map<?, ?> checkTable(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Map) {
				return (Map) o;
			} else {
				throw new IllegalArgumentException("Expected a map");
			}
		}

		@Override
		public ItemStack checkItemStack(final int index) {
			final Object o = checkAny(index);
			if(o instanceof ItemStack) {
				return (ItemStack) o;
			} else {
				throw new IllegalArgumentException("Expected an item stack");
			}
		}

		@Override
		public Object optAny(final int index, final Object def) {
			return inRange(index) ? parameters[index] : def;
		}

		@Override
		public boolean optBoolean(final int index, final boolean def) {
			return inRange(index) ? checkBoolean(index) : def;
		}

		@Override
		public int optInteger(final int index, final int def) {
			return inRange(index) ? checkInteger(index) : def;
		}

		@Override
		public double optDouble(final int index, final double def) {
			return inRange(index) ? checkDouble(index) : def;
		}

		@Override
		public String optString(final int index, final String def) {
			return inRange(index) ? checkString(index) : def;
		}

		@Override
		public byte[] optByteArray(final int index, final byte[] def) {
			return inRange(index) ? checkByteArray(index) : def;
		}

		@Override
		@SuppressWarnings("rawtypes")
		public Map<?, ?> optTable(final int index, final Map def) {
			return inRange(index) ? checkTable(index) : def;
		}

		@Override
		public ItemStack optItemStack(final int index, final ItemStack def) {
			return inRange(index) ? checkItemStack(index) : def;
		}

		@Override
		public boolean isBoolean(final int index) {
			return inRange(index) && parameters[index] instanceof Boolean;
		}

		@Override
		public boolean isInteger(final int index) {
			return inRange(index) && parameters[index] instanceof Integer;
		}

		@Override
		public boolean isDouble(final int index) {
			return inRange(index) && parameters[index] instanceof Double;
		}

		@Override
		public boolean isString(final int index) {
			return inRange(index) && parameters[index] instanceof String;
		}

		@Override
		public boolean isByteArray(final int index) {
			return inRange(index) && parameters[index] instanceof byte[];
		}

		@Override
		public boolean isTable(final int index) {
			return inRange(index) && parameters[index] instanceof Map;
		}

		@Override
		public boolean isItemStack(final int index) {
			return inRange(index) && parameters[index] instanceof ItemStack;
		}

		@Override
		public Object[] toArray() {
			return Arrays.stream(parameters).map(i -> (i instanceof byte[]) ? StandardCharsets.UTF_8.decode(ByteBuffer.wrap((byte[]) i)) : i).toArray();
		}
	}

	/**
	 * The name of the NBT tag that holds the {@link #target} field.
	 */
	private static final String NBT_TARGET = "target";

	/**
	 * The name of the NBT tag that holds the {@link #method} field.
	 */
	private static final String NBT_METHOD = "method";

	/**
	 * The name of the NBT tag that holds the {@link #parameters} field.
	 */
	private static final String NBT_PARAMETERS = "parameters";

	/**
	 * The parameters to pass to the method.
	 */
	protected final Object[] parameters;

	/**
	 * The opaque values referenced by this method call.
	 */
	private final List<ValueReference> values;

	/**
	 * Loads a {@code MethodCall} that was previously saved.
	 *
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 * @param valuePool The value pool.
	 * @param descriptors The descriptor table, which must already have been
	 * restored.
	 * @return The new {@code MethodCall} object.
	 */
	public static MethodCall load(final NBTTagCompound root, final ReferencedValue[] valuePool, final DescriptorTable descriptors) {

		final byte targetTag = root.func_150299_b(NBT_TARGET);
		final byte methodTag = root.func_150299_b(NBT_METHOD);
		if(targetTag == Constants.NBT.TAG_STRING && methodTag == Constants.NBT.TAG_STRING) {
			// Target and method are both strings → this is a component method
			// call.
			return new Component(root, valuePool, descriptors);
		} else if(targetTag == Constants.NBT.TAG_INT && methodTag == Constants.NBT.TAG_STRING) {
			// Target is an integer, method is a string → this is a value
			// regular method call.
			return new ValueRegular(root, valuePool, descriptors);
		} else if(targetTag == Constants.NBT.TAG_INT && methodTag == Constants.NBT.TAG_INT) {
			// Target and method are both integers → this is a value special
			// method call.
			return new ValueSpecial(root, valuePool, descriptors);
		} else {
			// WTF?
			throw new RuntimeException("Corrupt NBT data");
		}
	}

	/**
	 * Constructs a new {@code MethodCall}.
	 *
	 * @param parameters The parameters to pass to the method.
	 * @param values The opaque values referenced by this method call.
	 */
	protected MethodCall(final Object[] parameters, final List<ValueReference> values) {
		super();
		this.parameters = Objects.requireNonNull(parameters);
		this.values = Objects.requireNonNull(values);
	}

	/**
	 * Loads a {@code MethodCall} that was previously saved.
	 *
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 * @param valuePool The value pool.
	 * @param descriptors The descriptor table, which must already have been
	 * restored.
	 */
	protected MethodCall(final NBTTagCompound root, final ReferencedValue[] valuePool, final DescriptorTable descriptors) {
		super();

		// When save() persisted the MethodCall, if it encountered any opaque
		// values, it allocated new descriptors for them and represented them
		// in the NBT (either directly, in the case of target, or inside CBOR,
		// in the case of parameters) via those descriptors. Those descriptors
		// were not intentionally created by the Wasm module instance; rather,
		// they only exist because save() created them. We should therefore
		// keep track of them as we load, and close them at the end.
		final ArrayList<Integer> descriptorsInParameters = new ArrayList<Integer>();

		// Load the parameters.
		{
			final byte[] bytes = root.getByteArray(NBT_PARAMETERS);
			try {
				parameters = CBOR.toJavaArray(ByteBuffer.wrap(bytes), descriptors, descriptorsInParameters::add, Optional.empty());
			} catch(final CBORDecodeException exp) {
				throw new RuntimeException("Save data is corrupt: MethodCall contains invalid CBOR", exp);
			} catch(final BadDescriptorException exp) {
				throw new RuntimeException("Save data is corrupt: MethodCall refers to closed descriptor", exp);
			} catch(final MemoryFaultException exp) {
				throw new RuntimeException("Logic error: CBOR decode without reference to linear memory cannot throw MemoryFaultException", exp);
			}
		}

		// Collect all the opaque values referenced by the parameters.
		values = Collections.unmodifiableList(descriptorsInParameters.stream().map((descriptor) -> {
			try {
				return descriptors.get(descriptor);
			} catch(final BadDescriptorException exp) {
				// This cannot throw BadDescriptorException because, if it did,
				// CBOR.toJavaArray would have failed.
				throw new RuntimeException("Impossible exception", exp);
			}
		}).collect(Collectors.toList()));

		// Close all the descriptors that were referred to during loading of this object.
		descriptorsInParameters.stream().forEach(descriptor -> {
			try {
				descriptors.close(descriptor);
			} catch(final BadDescriptorException exp) {
				// This should be impossible if we just used the descriptor
				// during reconstruction of the data!
				throw new RuntimeException(exp);
			}
		});
	}

	/**
	 * Tries to perform the call directly.
	 *
	 * @param machine The machine.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws InProgressException If the method must be invoked indirectly
	 * (the call is <em>not</em> pended in this case; the caller must do that).
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	public final Object[] invokeDirect(final Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, InProgressException, BadParametersException, OtherException {
		return invoke(machine, true);
	}

	/**
	 * Tries to perform the call indirectly.
	 *
	 * @param machine The machine.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	public final Object[] invokeIndirect(final Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, BadParametersException, OtherException {
		try {
			return invoke(machine, false);
		} catch(final InProgressException exp) {
			throw new RuntimeException("Impossible exception thrown", exp);
		}
	}

	/**
	 * Tries to perform the call.
	 *
	 * @param machine The machine.
	 * @param direct {@code true} if the current call is being made from the
	 * computer thread, or {@code false} if it is being made from the server
	 * thread.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws InProgressException If the method must be invoked indirectly
	 * (the call is <em>not</em> pended in this case; the caller must do that).
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	private Object[] invoke(final Machine machine, final boolean direct) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, InProgressException, BadParametersException, OtherException {
		// Sanity check.
		Objects.requireNonNull(machine);

		// Perform any preliminary checks, and, if a Callback object is
		// available for this method, verify that the directness of the call is
		// correct, nothing that direct calls can also be called indirectly but
		// indirect calls cannot be called directly.
		final Callback callback = getCallback(machine);
		if(callback != null) {
			if(direct && !callback.direct()) {
				throw new InProgressException();
			}
		}

		// Perform the call.
		final Object[] result;
		try {
			result = invokeImpl(machine);
		} catch(final NoSuchMethodException exp) {
			throw new NoSuchComponentOrValueMethodException();
		} catch(final LimitReachedException exp) {
			if(direct) {
				throw new InProgressException();
			} else {
				throw new RuntimeException("Indirect method call returned LimitReachedException, which should be impossible");
			}
		} catch(final IllegalArgumentException exp) {
			throw new BadParametersException(exp);
		} catch(final Throwable t) {
			throw new OtherException(t);
		}
		return result != null ? result : OCWasm.ZERO_OBJECTS;
	}

	/**
	 * Saves the {@code MethodCall} into an NBT structure.
	 *
	 * @param valuePool The value pool to use to save opaque values.
	 * @param descriptorAlloc The descriptor allocator to use to save opaque
	 * values that need descriptors.
	 * @return The created NBT compound.
	 */
	public NBTTagCompound save(final ValuePool valuePool, final DescriptorTable.Allocator descriptorAlloc) {
		final NBTTagCompound root = new NBTTagCompound();
		root.setByteArray(NBT_PARAMETERS, CBOR.toCBOR(parameters, descriptorAlloc));
		return root;
	}

	/**
	 * Disposes of all resources in the object.
	 *
	 * Subclasses must call this method and then dispose of any resources held
	 * within themselves.
	 */
	@Override
	public void close() {
		values.forEach(ValueReference::close);
	}

	/**
	 * Returns the callback object to check for proper invocation, returns
	 * {@code null} if no callback should be checked, or throws an exception if
	 * preliminary checking for method validity failed.
	 *
	 * @param machine The machine.
	 * @return The callback to examine.
	 * @throws NoSuchComponentException If the target is a component and the
	 * component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist on the target.
	 */
	protected abstract Callback getCallback(Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException;

	/**
	 * Performs the core logic of invoking the method.
	 *
	 * @param machine The machine.
	 * @return The result, converted to proper types if that is not done by
	 * OpenComputers.
	 * @throws Exception If the method call fails.
	 */
	protected abstract Object[] invokeImpl(Machine machine) throws Exception;

	/**
	 * A method call to a regular method on a component.
	 */
	public static final class Component extends MethodCall {
		/**
		 * The target component address on which to invoke a method.
		 */
		public final UUID target;

		/**
		 * The method name.
		 */
		public final String method;

		/**
		 * Constructs a new {@code Component}.
		 *
		 * @param address The UUID of the component.
		 * @param method The name of the method.
		 * @param parameters The parameters to pass to the method.
		 * @param values The opaque values referenced by {@code parameters}.
		 */
		public Component(final UUID address, final String method, final Object[] parameters, final List<ValueReference> values) {
			super(parameters, values);
			target = Objects.requireNonNull(address);
			this.method = Objects.requireNonNull(method);
		}

		/**
		 * Loads a {@code Component} that was previously saved.
		 *
		 * @param root The compound that was previously returned from {@link
		 * #save}.
		 * @param valuePool The value pool.
		 * @param descriptors The descriptor table, which must already have
		 * been restored.
		 */
		public Component(final NBTTagCompound root, final ReferencedValue[] valuePool, final DescriptorTable descriptors) {
			super(root, valuePool, descriptors);
			target = UUID.fromString(root.getString(NBT_TARGET));
			method = root.getString(NBT_METHOD);
		}

		@Override
		protected Callback getCallback(final Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException {
			final Callback cb = machine.methods(ComponentUtils.getComponent(machine, target.toString()).host()).get(method);
			if(cb == null) {
				throw new NoSuchComponentOrValueMethodException();
			}
			return cb;
		}

		@Override
		protected Object[] invokeImpl(final Machine machine) throws Exception {
			return machine.invoke(target.toString(), method, parameters);
		}

		@Override
		public NBTTagCompound save(final ValuePool valuePool, final DescriptorTable.Allocator descriptorAlloc) {
			final NBTTagCompound root = super.save(valuePool, descriptorAlloc);
			root.setString(NBT_TARGET, target.toString());
			root.setString(NBT_METHOD, method);
			return root;
		}
	}

	/**
	 * A method call to a regular method on an opaque value.
	 */
	public static final class ValueRegular extends MethodCall {
		/**
		 * The target opaque value on which to invoke a method.
		 */
		public final ValueReference target;

		/**
		 * The method name.
		 */
		public final String method;

		/**
		 * Constructs a new {@code ValueRegular}.
		 *
		 * @param value The opaque value, which is cloned.
		 * @param method The name of the method.
		 * @param parameters The parameters to pass to the method.
		 * @param values The opaque values referenced by {@code parameters}.
		 */
		public ValueRegular(final ValueReference value, final String method, final Object[] parameters, final List<ValueReference> values) {
			super(parameters, values);
			target = value.clone();
			this.method = Objects.requireNonNull(method);
		}

		/**
		 * Loads a {@code ValueRegular} that was previously saved.
		 *
		 * @param root The compound that was previously returned from {@link
		 * #save}.
		 * @param valuePool The value pool.
		 * @param descriptors The descriptor table, which must already have
		 * been restored.
		 */
		public ValueRegular(final NBTTagCompound root, final ReferencedValue[] valuePool, final DescriptorTable descriptors) {
			super(root, valuePool, descriptors);
			target = new ValueReference(valuePool[root.getInteger(NBT_TARGET)]);
			method = root.getString(NBT_METHOD);
		}

		@Override
		protected Callback getCallback(final Machine machine) throws NoSuchComponentOrValueMethodException {
			final Callback cb = machine.methods(target.get()).get(method);
			if(cb == null) {
				throw new NoSuchComponentOrValueMethodException();
			}
			return cb;
		}

		@Override
		protected Object[] invokeImpl(final Machine machine) throws Exception {
			return machine.invoke(target.get(), method, parameters);
		}

		@Override
		public NBTTagCompound save(final ValuePool valuePool, final DescriptorTable.Allocator descriptorAlloc) {
			final NBTTagCompound root = super.save(valuePool, descriptorAlloc);
			root.setInteger(NBT_TARGET, valuePool.store(target.get()));
			root.setString(NBT_METHOD, method);
			return root;
		}

		@Override
		public void close() {
			super.close();
			target.close();
		}
	}

	/**
	 * A method call to a special method on an opaque value.
	 */
	public static final class ValueSpecial extends MethodCall {
		/**
		 * The possible special methods that can be performed on an opaque
		 * value.
		 *
		 * The elements of this enumeration are stored by ordinal in NBT in
		 * world saves. They must not be reordered or deleted.
		 */
		public enum Method {
			/**
			 * The “call” method, which calls a callable opaque value.
			 */
			CALL {
				@Override
				public Object[] invoke(final Value value, final Machine machine, final Arguments params) throws NoSuchComponentOrValueMethodException {
					try {
						return value.call(machine, params);
					} catch(final RuntimeException exp) {
						if(exp.getClass() == RuntimeException.class) {
							// It’s not certain, but this is *probably* thrown
							// by the AbstractValue.call() implementation and
							// is telling us that this Value is not callable.
							// Unfortunately OpenComputers doesn’t give a
							// reliable way to distinguish that from other
							// RuntimeExceptions.
							throw new NoSuchComponentOrValueMethodException();
						} else {
							throw exp;
						}
					}
				}
			},

			/**
			 * The “apply” method, which reads from an index of the opaque
			 * value.
			 */
			APPLY {
				@Override
				public Object[] invoke(final Value value, final Machine machine, final Arguments params) {
					return new Object[]{value.apply(machine, params)};
				}
			},

			/**
			 * The “unapply” method, which writes to an index of the opaque
			 * value.
			 */
			UNAPPLY {
				@Override
				public Object[] invoke(final Value value, final Machine machine, final Arguments params) {
					value.unapply(machine, params);
					return OCWasm.ZERO_OBJECTS;
				}
			};

			/**
			 * Invokes the special method.
			 *
			 * @param value The value to invoke on.
			 * @param machine The machine doing the invoking.
			 * @param params The parameters to pass.
			 * @return The method’s return value.
			 * @throws NoSuchComponentOrValueMethodException If the method does
			 * not exist.
			 */
			public abstract Object[] invoke(Value value, Machine machine, Arguments params) throws NoSuchComponentOrValueMethodException;
		}

		/**
		 * The target opaque value on which to invoke a method.
		 */
		public final ValueReference target;

		/**
		 * The method.
		 */
		public final Method method;

		/**
		 * Constructs a new {@code ValueSpecial}.
		 *
		 * @param value The opaque value, which is cloned.
		 * @param method The method.
		 * @param parameters The parameters to pass to the method.
		 * @param values The opaque values referenced by {@code parameters}.
		 */
		public ValueSpecial(final ValueReference value, final Method method, final Object[] parameters, final List<ValueReference> values) {
			super(parameters, values);
			target = value.clone();
			this.method = Objects.requireNonNull(method);
		}

		/**
		 * Loads a {@code ValueSpecial} that was previously saved.
		 *
		 * @param root The compound that was previously returned from {@link
		 * #save}.
		 * @param valuePool The value pool.
		 * @param descriptors The descriptor table, which must already have
		 * been restored.
		 */
		public ValueSpecial(final NBTTagCompound root, final ReferencedValue[] valuePool, final DescriptorTable descriptors) {
			super(root, valuePool, descriptors);
			target = new ValueReference(valuePool[root.getInteger(NBT_TARGET)]);
			final int ordinal = root.getInteger(NBT_METHOD);
			method = Arrays.stream(Method.values()).filter(i -> i.ordinal() == ordinal).findAny().get();
		}

		/**
		 * Returns {@code null} because special methods always exist (though
		 * they may or may not do anything useful at runtime) and are always
		 * direct.
		 *
		 * @param machine The machine.
		 * @return {@code null}.
		 */
		@Override
		protected Callback getCallback(final Machine machine) {
			return null;
		}

		@Override
		protected Object[] invokeImpl(final Machine machine) throws NoSuchComponentOrValueMethodException {
			// Machine doesn’t have an API for making value special method
			// calls cleanly, so we have to do the raw method call and then
			// convert the return value(s).
			return OCWasm.convertValues(method.invoke(target.get(), machine, new Arguments(parameters)));
		}

		@Override
		public NBTTagCompound save(final ValuePool valuePool, final DescriptorTable.Allocator descriptorAlloc) {
			final NBTTagCompound root = super.save(valuePool, descriptorAlloc);
			root.setInteger(NBT_TARGET, valuePool.store(target.get()));
			root.setInteger(NBT_METHOD, method.ordinal());
			return root;
		}

		@Override
		public void close() {
			super.close();
			target.close();
		}
	}
}
