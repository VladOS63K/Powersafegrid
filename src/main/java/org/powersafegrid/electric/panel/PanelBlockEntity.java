package org.powersafegrid.electric.panel;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.electric.PSGItems;
import org.powersafegrid.electric.VoltageRelayBlockEntity;

import java.util.List;

/**
 * Panel block entity: keeps the installed modules (breakers and relay), their
 * trip state and the per-slot switched wires. Terminal 2i is the top (input)
 * contact of slot i, terminal 2i+1 the bottom (output) contact.
 */
public class PanelBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {
    public static final float RELAY_OVERLOAD_CURRENT = 60f;
    public static final float RELAY_EXPLODE_CURRENT = 100f;
    public static final int BREAKER_TRIP_TICKS = 15;
    public static final int BREAKER_FAST_TRIP_TICKS = 5;
    public static final int RELAY_RECONNECT_DELAY = 60;
    public static final float RELAY_DEFAULT_LOW = 90f;
    public static final float RELAY_DEFAULT_HIGH = 130f;

    public enum PanelModule {
        EMPTY, BREAKER_8, BREAKER_10, BREAKER_16, BREAKER_63, RELAY;

        public static final PanelModule[] VALUES = values();

        public float rating() {
            return switch (this) {
                case BREAKER_8 -> 8f;
                case BREAKER_10 -> 10f;
                case BREAKER_16 -> 16f;
                case BREAKER_63 -> 63f;
                default -> 0f;
            };
        }

        public ItemStack stack() {
            return switch (this) {
                case BREAKER_8 -> new ItemStack(PSGItems.BREAKER_8A.get());
                case BREAKER_10 -> new ItemStack(PSGItems.BREAKER_10A.get());
                case BREAKER_16 -> new ItemStack(PSGItems.BREAKER_16A.get());
                case BREAKER_63 -> new ItemStack(PSGItems.BREAKER_63A.get());
                case RELAY -> new ItemStack(PSGItems.VOLTAGE_RELAY.get());
                default -> ItemStack.EMPTY;
            };
        }
    }

    public static PanelModule moduleOfItem(ItemStack stack) {
        if (stack.is(PSGItems.BREAKER_8A.get()))
            return PanelModule.BREAKER_8;
        if (stack.is(PSGItems.BREAKER_10A.get()))
            return PanelModule.BREAKER_10;
        if (stack.is(PSGItems.BREAKER_16A.get()))
            return PanelModule.BREAKER_16;
        if (stack.is(PSGItems.BREAKER_63A.get()))
            return PanelModule.BREAKER_63;
        if (stack.is(PSGItems.VOLTAGE_RELAY.get()))
            return PanelModule.RELAY;
        return PanelModule.EMPTY;
    }

    private final int slotCount;
    private PanelModule[] modules;
    private final boolean[] tripped;     // breaker tripped by overcurrent
    private final boolean[] manualOff;   // breaker switched off by hand
    private final int[] tripTimers;

    // Relay module (2 adjacent slots)
    private int relaySlot = -1;
    private boolean relayClosed = true;
    private boolean relayOverloaded;
    private int relayReconnectTimer;
    private int relayOverloadTimer;

    private SwitchedWire[] slotWires;

    private boolean dirty; // rebuild circuit on next tick

    public PanelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        slotCount = state.getBlock() instanceof PanelBlock panel ? panel.getSlotCount() : 5;
        modules = new PanelModule[slotCount];
        tripped = new boolean[slotCount];
        manualOff = new boolean[slotCount];
        tripTimers = new int[slotCount];
        for (int i = 0; i < slotCount; i++)
            modules[i] = PanelModule.EMPTY;
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electricBehaviour.reducedSync();
    }

    public int getSlotCount() {
        return slotCount;
    }

    public PanelModule getModule(int slot) {
        return modules[slot];
    }

    public boolean isFree(int slot) {
        return slot >= 0 && slot < slotCount && modules[slot] == PanelModule.EMPTY;
    }

    public int firstFreeSlot() {
        for (int i = 0; i < slotCount; i++)
            if (modules[i] == PanelModule.EMPTY)
                return i;
        return -1;
    }

    /** First position where a two-slot module fits; -1 if none. */
    public int findFreePair(int preferred) {
        if (preferred >= 0 && preferred + 1 < slotCount
                && modules[preferred] == PanelModule.EMPTY && modules[preferred + 1] == PanelModule.EMPTY)
            return preferred;
        for (int i = 0; i + 1 < slotCount; i++)
            if (modules[i] == PanelModule.EMPTY && modules[i + 1] == PanelModule.EMPTY)
                return i;
        return -1;
    }

    public int firstOccupiedSlot() {
        for (int i = 0; i < slotCount; i++)
            if (modules[i] != PanelModule.EMPTY)
                return i;
        return -1;
    }

    public ItemStack getModuleStack(int slot) {
        return modules[slot].stack();
    }

    /** Block state of the module model to render in the given slot (null if empty). */
    public BlockState getModuleState(int slot) {
        if (slot < 0 || slot >= slotCount)
            return null;
        return switch (modules[slot]) {
            case BREAKER_8 -> PSGBlocks.MODULE_BREAKER.get().defaultBlockState()
                    .setValue(org.powersafegrid.electric.panel.PanelModuleBlock.RATING, 1);
            case BREAKER_10 -> PSGBlocks.MODULE_BREAKER.get().defaultBlockState()
                    .setValue(org.powersafegrid.electric.panel.PanelModuleBlock.RATING, 2);
            case BREAKER_16 -> PSGBlocks.MODULE_BREAKER.get().defaultBlockState()
                    .setValue(org.powersafegrid.electric.panel.PanelModuleBlock.RATING, 3);
            case BREAKER_63 -> PSGBlocks.MODULE_BREAKER.get().defaultBlockState()
                    .setValue(org.powersafegrid.electric.panel.PanelModuleBlock.RATING, 4);
            case RELAY -> PSGBlocks.MODULE_RELAY.get().defaultBlockState();
            default -> null;
        };
    }

    public void install(int slot, PanelModule module) {
        if (module == PanelModule.RELAY) {
            relaySlot = slot;
            relayClosed = true;
            relayOverloaded = false;
            relayReconnectTimer = 0;
            modules[slot] = PanelModule.RELAY;
            modules[slot + 1] = PanelModule.RELAY;
            tripped[slot] = false;
            tripped[slot + 1] = false;
            manualOff[slot] = false;
            manualOff[slot + 1] = false;
            ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.8f, 1.2f);
        } else {
            modules[slot] = module;
            tripped[slot] = false;
            manualOff[slot] = false;
            ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.6f, 1.2f);
        }
        dirty = true;
        setChanged();
        notifyUpdate();
    }

    public ItemStack extract(int slot) {
        if (slot < 0 || slot >= slotCount || modules[slot] == PanelModule.EMPTY)
            return ItemStack.EMPTY;
        var module = modules[slot];
        if (module == PanelModule.RELAY) {
            // The relay pair lives at (relaySlot, relaySlot+1): free both slots
            // no matter which half the player extracts.
            int start = relaySlot >= 0 && relaySlot < slotCount && modules[relaySlot] == PanelModule.RELAY
                    ? relaySlot
                    : (slot + 1 < slotCount && modules[slot + 1] == PanelModule.RELAY ? slot : slot - 1);
            start = Mth.clamp(start, 0, slotCount - 2);
            relaySlot = -1;
            modules[start] = PanelModule.EMPTY;
            modules[start + 1] = PanelModule.EMPTY;
            tripped[start] = false;
            manualOff[start] = false;
            tripped[start + 1] = false;
            manualOff[start + 1] = false;
            ModdedSoundEvents.CONTACTOR_OFF.playOnServer(level, worldPosition, 0.7f, 0.8f);
        } else {
            modules[slot] = PanelModule.EMPTY;
            tripped[slot] = false;
            manualOff[slot] = false;
            ModdedSoundEvents.CONTACTOR_OFF.playOnServer(level, worldPosition, 0.6f, 0.8f);
        }
        dirty = true;
        setChanged();
        notifyUpdate();
        return module.stack();
    }

    /** Hand operation on a slot: reset a tripped breaker, toggle it, reset relay overload. */
    public void operate(int slot, Player player) {
        var module = modules[slot];
        if (module == PanelModule.EMPTY)
            return;
        if (module == PanelModule.RELAY) {
            if (relayOverloaded) {
                relayOverloaded = false;
                relayClosed = true;
                message(player, "relay_reset");
                PowerSafeGrid.LOGGER.info("Panel at {} relay overload reset", worldPosition.toShortString());
            } else {
                message(player, relayClosed ? "relay_running" : "relay_open");
            }
            setChanged();
            notifyUpdate();
            return;
        }
        if (tripped[slot]) {
            // Reset the tripped breaker.
            tripped[slot] = false;
            tripTimers[slot] = 0;
            applyWireStates(slot, true);
            ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.6f, 1.3f);
            message(player, "reset");
        } else {
            manualOff[slot] = !manualOff[slot];
            applyWireStates(slot, !manualOff[slot]);
            ModdedSoundEvents.CONTACTOR_OFF.playOnServer(level, worldPosition, 0.5f, 0.9f);
        }
        setChanged();
        notifyUpdate();
    }

    public void message(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(PowerSafeGrid.MOD_ID + ".message.panel." + key, args), true);
    }

    private void applyWireStates(int slot, boolean closed) {
        if (slotWires == null || slot >= slotWires.length || slotWires[slot] == null)
            return;
        slotWires[slot].setState(false);
        slotWires[slot].setState(closed);
    }

    @Override
    public void unpaused() {
        electricBehaviour.rebuildCircuit(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;
        if (level.isClientSide)
            return;
        if (dirty) {
            dirty = false;
            electricBehaviour.rebuildCircuit(false);
        }
    }

    @Override
    public void electricalTick() {
        if (level.isClientSide || slotWires == null || slotWires.length != slotCount)
            return;
        applyPower(slotWires[0]);

        // Breakers
        for (int i = 0; i < slotCount; i++) {
            var module = modules[i];
            if (module == PanelModule.EMPTY || module == PanelModule.RELAY || tripped[i] || manualOff[i])
                continue;
            var wire = slotWires[i];
            if (wire == null || !wire.isConverged())
                continue;
            double current = Math.abs(wire.current());
            float rating = module.rating();
            if (current >= 3 * rating) {
                if (++tripTimers[i] >= BREAKER_FAST_TRIP_TICKS)
                    tripBreaker(i, current);
            } else if (current > rating) {
                if (++tripTimers[i] >= BREAKER_TRIP_TICKS)
                    tripBreaker(i, current);
            } else {
                tripTimers[i] = 0;
            }
        }

        // Relay module
        if (relaySlot >= 0 && modules[relaySlot] == PanelModule.RELAY) {
            int other = relaySlot + 1;
            if (relayOverloaded)
                return;
            if (slotWires[relaySlot] == null || !slotWires[relaySlot].isConverged())
                return;

            double iOut = Math.abs(slotWires[relaySlot].current());
            if (iOut >= RELAY_EXPLODE_CURRENT) {
                if (++relayOverloadTimer >= 10) {
                    PowerSafeGrid.LOGGER.warn("Panel at {} relay exploded, current {} A",
                            worldPosition.toShortString(), String.format("%.1f", iOut));
                    ThermalBehaviour.explode(level, worldPosition, getBlockState(), 4f);
                }
                return;
            }
            if (iOut > RELAY_OVERLOAD_CURRENT) {
                if (++relayOverloadTimer >= 5) {
                    relayOverloaded = true;
                    applyWireStates(relaySlot, false);
                    applyWireStates(other, false);
                    spawnTripEffects();
                    PowerSafeGrid.LOGGER.info("Panel at {} relay tripped on overload, current {} A",
                            worldPosition.toShortString(), String.format("%.1f", iOut));
                    ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 1f, 0.8f);
                    setChanged();
                    notifyUpdate();
                }
                return;
            }
            relayOverloadTimer = 0;

            // Voltage window across the relay's two TOP contacts:
            // phase = top of the first slot, neutral = top of the second slot.
            int tL = 2 * relaySlot;
            int tN = 2 * (relaySlot + 1);
            boolean pairWired = electricBehaviour.hasTerminal(tN)
                    && electricBehaviour.getTerminal(tL).getNetwork() != null
                    && electricBehaviour.getTerminal(tN).getNetwork() != null;
            double u = pairWired
                    ? Math.abs(electricBehaviour.getTerminal(tL).getVoltage()
                            - electricBehaviour.getTerminal(tN).getVoltage())
                    : 0;

            if (relayClosed) {
                if (u > 1 && (u < RELAY_DEFAULT_LOW || u > RELAY_DEFAULT_HIGH)) {
                    relayClosed = false;
                    applyWireStates(relaySlot, false);
                    applyWireStates(other, false);
                    spawnTripEffects();
                    ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 0.8f, 1f);
                    PowerSafeGrid.LOGGER.info("Panel at {} relay opened, input {} V", worldPosition.toShortString(),
                            String.format("%.0f", u));
                    setChanged();
                    notifyUpdate();
                }
            } else {
                if (u > 1 && u >= RELAY_DEFAULT_LOW && u <= RELAY_DEFAULT_HIGH) {
                    if (++relayReconnectTimer >= RELAY_RECONNECT_DELAY) {
                        relayClosed = true;
                        applyWireStates(relaySlot, true);
                        applyWireStates(other, true);
                        ModdedSoundEvents.CONTACTOR_ON.playOnServer(level, worldPosition, 0.6f, 1.2f);
                        setChanged();
                        notifyUpdate();
                    }
                } else {
                    relayReconnectTimer = 0;
                }
            }
        }
    }

    private void tripBreaker(int slot, double current) {
        tripped[slot] = true;
        tripTimers[slot] = 0;
        applyWireStates(slot, false);
        ModdedSoundEvents.FUSE_POPS.playOnServer(level, worldPosition, 0.8f, 1f);
        PowerSafeGrid.LOGGER.info("Panel at {} breaker in slot {} tripped, current {} A",
                worldPosition.toShortString(), slot + 1, String.format("%.1f", current));
        setChanged();
        notifyUpdate();
    }

    private void spawnTripEffects() {
        if (level == null || !level.isClientSide)
            return;
        var center = worldPosition.getCenter();
        for (int j = 0; j < 10; ++j) {
            double dx = level.random.nextGaussian() * 0.1;
            double dy = level.random.nextDouble() * 0.3;
            double dz = level.random.nextGaussian() * 0.1;
            level.addParticle(j % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    center.x + dx, center.y + 0.5 + dy, center.z + dz, 0, 0.02, 0);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        int[] saved = tag.getIntArray("Modules");
        for (int i = 0; i < slotCount; i++) {
            int ordinal = i < saved.length ? saved[i] : 0;
            modules[i] = PanelModule.VALUES[ordinal % PanelModule.VALUES.length];
        }
        int[] trippedSaved = tag.getIntArray("Tripped");
        for (int i = 0; i < slotCount; i++)
            tripped[i] = i < trippedSaved.length && trippedSaved[i] != 0;
        int[] manualSaved = tag.getIntArray("ManualOff");
        for (int i = 0; i < slotCount; i++)
            manualOff[i] = i < manualSaved.length && manualSaved[i] != 0;
        relaySlot = tag.contains("RelaySlot") ? tag.getInt("RelaySlot") : -1;
        relayClosed = tag.getBoolean("RelayClosed");
        relayOverloaded = tag.getBoolean("RelayOverloaded");
        dirty = true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        int[] saved = new int[slotCount];
        int[] trippedSaved = new int[slotCount];
        int[] manualSaved = new int[slotCount];
        for (int i = 0; i < slotCount; i++) {
            saved[i] = modules[i].ordinal();
            trippedSaved[i] = tripped[i] ? 1 : 0;
            manualSaved[i] = manualOff[i] ? 1 : 0;
        }
        tag.putIntArray("Modules", saved);
        tag.putIntArray("Tripped", trippedSaved);
        tag.putIntArray("ManualOff", manualSaved);
        tag.putInt("RelaySlot", relaySlot);
        tag.putBoolean("RelayClosed", relayClosed);
        tag.putBoolean("RelayOverloaded", relayOverloaded);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        // buildCircuit can run from the super constructor, before the fields
        // are initialized: derive the count from the block state and treat the
        // module storage as empty while it is still null.
        int n = getBlockState().getBlock() instanceof PanelBlock panel ? panel.getSlotCount() : slotCount;
        builder.setTerminalCount(n * 2);
        slotWires = new SwitchedWire[n];
        for (int i = 0; i < n; i++) {
            var module = modules != null && i < modules.length ? modules[i] : PanelModule.EMPTY;
            boolean closed = module != PanelModule.EMPTY && !(tripped != null && tripped[i])
                    && !(manualOff != null && manualOff[i])
                    && (module != PanelModule.RELAY || (relayClosed && !relayOverloaded));
            if (module == PanelModule.EMPTY) {
                slotWires[i] = null;
                continue;
            }
            slotWires[i] = builder.connectSwitch(module == PanelModule.RELAY ? 0.05f : 0.02f,
                    builder.terminalNode(2 * i), builder.terminalNode(2 * i + 1), closed);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.panel.header").withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < slotCount; i++) {
            var module = modules[i];
            Component line;
            if (module == PanelModule.EMPTY) {
                line = Component.literal("  " + (i + 1) + ": ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.panel.empty").withStyle(ChatFormatting.DARK_GRAY));
            } else if (module == PanelModule.RELAY) {
                boolean open = !relayClosed || relayOverloaded;
                line = Component.literal("  " + (i + 1) + "-" + (i + 2) + ": ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.translatable(PowerSafeGrid.MOD_ID + ".goggles.panel.relay").withStyle(ChatFormatting.LIGHT_PURPLE))
                        .append(Component.literal(open ? " [" : " [")
                                .append(Component.translatable(PowerSafeGrid.MOD_ID + (relayOverloaded ? ".goggles.panel.relay_overload"
                                        : relayClosed ? ".goggles.panel.relay_closed" : ".goggles.panel.relay_open")))
                                .append("]")
                                .withStyle(open ? ChatFormatting.RED : ChatFormatting.GREEN));
                i++; // relay occupies two slots
            } else {
                ChatFormatting color = tripped[i] ? ChatFormatting.RED
                        : manualOff[i] ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
                line = Component.literal("  " + (i + 1) + ": ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(module.rating() + " A").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(tripped[i] ? " [" : " [")
                                .append(Component.translatable(PowerSafeGrid.MOD_ID
                                        + (tripped[i] ? ".goggles.panel.tripped" : manualOff[i] ? ".goggles.panel.off" : ".goggles.panel.on")))
                                .append("]")
                                .withStyle(color));
            }
            tooltip.add(line);
        }
        return true;
    }
}
