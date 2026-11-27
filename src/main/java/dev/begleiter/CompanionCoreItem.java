package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Rechtsklick auf einen Block: Begleiter erscheint und gehört dem Spieler. */
public class CompanionCoreItem extends Item {
    public CompanionCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos pos = ctx.getClickedPos().relative(ctx.getClickedFace());
        CompanionEntity companion = CompanionMod.COMPANION.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (companion == null) return InteractionResult.FAIL;

        companion.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0f);
        companion.setOwner(player.getUUID());
        companion.setCustomName(Component.literal(CompanionEntity.DEFAULT_NAME));
        CustomData memory = ctx.getItemInHand().get(DataComponents.CUSTOM_DATA);
        CompoundTag data = memory == null ? new CompoundTag() : memory.copyTag();
        java.util.UUID soul = data.read("Soul", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        CompoundTag full = data;
        if (soul != null) {
            // Neuer Kern: Gedächtnis liegt im Weltspeicher. Fehlt es (andere Welt, schon wiederbelebt), bleibt der Kern.
            full = CompanionMemoryStore.get(level.getServer()).peek(soul);
            if (full == null) {
                player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("core.memory_missing")));
                return InteractionResult.FAIL;
            }
        }
        boolean revived = !full.isEmpty();
        if (revived) companion.loadMemory(full); // alter Kern: Gedächtnis steckt noch im Kern selbst
        if (soul != null) companion.setSoulId(soul);
        if (!level.addFreshEntity(companion)) return InteractionResult.FAIL; // nichts verbraucht
        if (soul != null) CompanionMemoryStore.get(level.getServer()).consume(soul); // erst nach bestätigter Rückkehr
        companion.tell(CompanionTexts.tr(revived ? "reply.revived" : "reply.hello"));
        ctx.getItemInHand().consume(1, player);
        return InteractionResult.SUCCESS_SERVER;
    }
}
