package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenuCommands;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Fragen, Gesprächsverhalten und eine freie Befehlszeile für alles, was keinen eigenen Knopf hat. */
public final class TalkPage implements ControlPage {
    private String draft = "";
    private int topic;
    private boolean confirmMemory;
    private EditBox input;

    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.TALK; }
    @Override public Item icon() { return Items.PAPER; }
    @Override public boolean quickBar() { return false; }

    @Override public void init(CompanionScreen s) {
        input = s.editBox(108, 51, 150, 18, s.tr("gui.begleiter.talk.input"), draft, CompanionMenuCommands.MAX_LENGTH,
                value -> draft = value, () -> send(s));
        s.button(261, 51, 41, 18, s.tr("gui.begleiter.talk.send"), ItemStack.EMPTY, s.tr("gui.begleiter.talk.send.tooltip"),
                () -> false, () -> send(s));
        s.commandButton(108, 86, 63, 18, "gui.begleiter.talk.status", ItemStack.EMPTY, () -> CompanionMenuCommands.STATUS);
        s.commandButton(173, 86, 63, 18, "gui.begleiter.talk.why", ItemStack.EMPTY, () -> CompanionMenuCommands.EXPLAIN);
        s.commandButton(238, 86, 64, 18, "gui.begleiter.talk.knowledge", ItemStack.EMPTY, () -> CompanionMenuCommands.KNOWLEDGE);
        String where = CompanionMenuCommands.WHERE_TOPICS.get(topic);
        s.button(108, 107, 95, 18, s.tr("gui.begleiter.talk.where", s.tr("gui.begleiter.talk.topic." + where)), ItemStack.EMPTY,
                s.tr("gui.begleiter.talk.where.tooltip"), () -> false,
                () -> { topic = (topic + 1) % CompanionMenuCommands.WHERE_TOPICS.size(); s.rebuildPage(); });
        s.button(206, 107, 46, 18, s.tr("gui.begleiter.talk.ask"), ItemStack.EMPTY, s.tr("gui.begleiter.talk.ask.tooltip"),
                () -> false, () -> s.sendCommand(CompanionMenuCommands.whereIs(CompanionMenuCommands.WHERE_TOPICS.get(topic))));
        s.commandButton(255, 107, 47, 18, "gui.begleiter.talk.schematics", ItemStack.EMPTY, () -> CompanionMenuCommands.SCHEMATICS);
        s.commandButton(108, 142, 63, 18, "gui.begleiter.talk.less", ItemStack.EMPTY, () -> CompanionMenuCommands.TALK_LESS);
        s.commandButton(173, 142, 63, 18, "gui.begleiter.talk.more", ItemStack.EMPTY, () -> CompanionMenuCommands.TALK_MORE);
        s.commandButton(238, 142, 64, 18, "gui.begleiter.talk.protect", ItemStack.EMPTY, () -> CompanionMenuCommands.PROTECT);
        s.commandButton(108, 163, 63, 18, "gui.begleiter.talk.yes", ItemStack.EMPTY, () -> CompanionMenuCommands.YES);
        s.commandButton(173, 163, 63, 18, "gui.begleiter.talk.no", ItemStack.EMPTY, () -> CompanionMenuCommands.NO);
        String memoryKey = confirmMemory ? "gui.begleiter.talk.memory.confirm" : "gui.begleiter.talk.memory";
        s.button(238, 163, 64, 18, s.tr(memoryKey), ItemStack.EMPTY, s.tr("gui.begleiter.talk.memory.tooltip"),
                () -> confirmMemory, () -> {
                    if (confirmMemory) s.sendCommand(CompanionMenuCommands.MEMORY_CLEAR);
                    confirmMemory = !confirmMemory;
                    s.rebuildPage();
                });
    }

    private void send(CompanionScreen s) {
        String text = draft.strip();
        if (text.isEmpty()) return;
        s.sendCommand(text, true);
        draft = "";
        if (input != null) input.setValue("");
    }

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.heading(g, s.tr("gui.begleiter.talk.free"), 108, 41, 194);
        s.heading(g, s.tr("gui.begleiter.talk.questions"), 108, 75, 194);
        s.heading(g, s.tr("gui.begleiter.talk.behavior"), 108, 131, 194);
        s.feedback(g, 194);
    }
}
