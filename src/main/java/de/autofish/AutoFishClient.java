package de.autofish;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-Angeln + automatisches Shift-Halten/Loslassen im Spannungs-Minispiel.
 *
 * Ablauf:
 *  1. Angel auswerfen, auf Biss warten (Bobber taucht ab).
 *  2. Nach dem Biss kurz warten (checkDelay Ticks). Kommt in der Zeit eine Actionbar
 *     mit "NN% Spannung", ist es ein Special Fish -> Minispiel.
 *  3. Minispiel: Spannung >= high -> Shift loslassen, Spannung <= low -> Shift halten.
 *  4. Kein Spannungs-Update mehr -> Minispiel vorbei, Shift loslassen, neu auswerfen.
 *  Sonst (normaler Fisch): einholen und neu auswerfen.
 */
public class AutoFishClient implements ClientModInitializer {

	private static final Pattern TENSION = Pattern.compile("(\\d{1,3})\\s*%\\s*Spannung");

	// Einstellbar per Befehl
	static boolean enabled = false;
	static boolean debug = false;
	static int low = 55;        // darunter: Shift halten (ziehen)
	static int high = 80;       // darueber: Shift loslassen (Schnur geben)
	static int checkDelay = 10; // Ticks nach Biss, bevor normal eingeholt wird

	// Zustand
	private static long age = 0;
	private static int tension = -1;
	private static long lastTensionTick = -1000;
	private static boolean holding = false;
	private static int cooldown = 0;
	private static int hookAge = 0;
	private static long biteAt = -1;

	@Override
	public void onInitializeClient() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String text = message.getString();
			if (debug && overlay) {
				chat("[AutoFish] Actionbar: " + text);
			}
			Matcher m = TENSION.matcher(text);
			if (m.find()) {
				tension = Integer.parseInt(m.group(1));
				lastTensionTick = age;
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(AutoFishClient::tick);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("autofish")
				.then(ClientCommandManager.literal("on").executes(c -> {
					enabled = true;
					c.getSource().sendFeedback(Component.literal("AutoFish AN"));
					return 1;
				}))
				.then(ClientCommandManager.literal("off").executes(c -> {
					enabled = false;
					releaseSneak();
					c.getSource().sendFeedback(Component.literal("AutoFish AUS"));
					return 1;
				}))
				.then(ClientCommandManager.literal("debug").executes(c -> {
					debug = !debug;
					c.getSource().sendFeedback(Component.literal("Debug: " + debug));
					return 1;
				}))
				.then(ClientCommandManager.literal("low")
					.then(ClientCommandManager.argument("wert", IntegerArgumentType.integer(0, 100)).executes(c -> {
						low = IntegerArgumentType.getInteger(c, "wert");
						c.getSource().sendFeedback(Component.literal("low = " + low));
						return 1;
					})))
				.then(ClientCommandManager.literal("high")
					.then(ClientCommandManager.argument("wert", IntegerArgumentType.integer(0, 100)).executes(c -> {
						high = IntegerArgumentType.getInteger(c, "wert");
						c.getSource().sendFeedback(Component.literal("high = " + high));
						return 1;
					})))
				.then(ClientCommandManager.literal("delay")
					.then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(0, 100)).executes(c -> {
						checkDelay = IntegerArgumentType.getInteger(c, "ticks");
						c.getSource().sendFeedback(Component.literal("delay = " + checkDelay + " Ticks"));
						return 1;
					})))
				.then(ClientCommandManager.literal("status").executes(c -> {
					c.getSource().sendFeedback(Component.literal(
						"an=" + enabled + " low=" + low + " high=" + high + " delay=" + checkDelay + " spannung=" + tension));
					return 1;
				}))
			));
	}

	private static void tick(Minecraft mc) {
		age++;
		LocalPlayer p = mc.player;
		if (!enabled || p == null || mc.gameMode == null) {
			if (holding) releaseSneak();
			return;
		}

		boolean inMinigame = tension >= 0 && age - lastTensionTick <= 20;

		if (inMinigame) {
			biteAt = -1;
			if (tension >= high) holding = false;
			else if (tension <= low) holding = true;
			mc.options.keyShift.setDown(holding);
			return;
		}

		// Minispiel vorbei
		if (holding || tension >= 0) {
			releaseSneak();
			tension = -1;
			cooldown = 20;
		}

		if (!p.getMainHandItem().is(Items.FISHING_ROD)) return;

		FishingHook hook = p.fishing;
		if (hook == null) {
			hookAge = 0;
			biteAt = -1;
			if (cooldown > 0) { cooldown--; return; }
			use(mc, p);
			return;
		}

		hookAge++;

		// Biss erkennen: Bobber im Wasser bekommt starken Abwaertsruck
		if (biteAt < 0 && hookAge > 30 && hook.isInWater() && hook.getDeltaMovement().y < -0.12) {
			biteAt = age;
		}

		if (biteAt >= 0 && age - biteAt >= checkDelay) {
			use(mc, p);          // normaler Fisch: einholen
			biteAt = -1;
			cooldown = 15;
		} else if (hookAge > 1200) {
			use(mc, p);          // Sicherheit: nach 60s einholen und neu werfen
			cooldown = 15;
		}
	}

	private static void use(Minecraft mc, LocalPlayer p) {
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		p.swing(InteractionHand.MAIN_HAND);
	}

	private static void releaseSneak() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options != null) mc.options.keyShift.setDown(false);
		holding = false;
	}

	private static void chat(String s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), false);
	}
}
