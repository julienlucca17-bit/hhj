package de.autofish;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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

	private static final Pattern TENSION = Pattern.compile("(\\d{1,3})\\s*%.{0,10}?Spannung", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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
	private static java.util.function.Consumer<Component> out = null;

	// Direktes Auslesen der Bildschirmanzeige (Actionbar/Titel), egal wie der Server sie schickt
	private static java.util.List<java.lang.reflect.Field> compFields = null;
	private static java.lang.reflect.Field timeField = null;
	private static String lastText = "";
	private static int lastTime = 0;
	private static long lastMiniTick = -1000;
	private static int forceHold = 0;

	// Entfernt unsichtbare Sonderzeichen (Server bauen Anzeigen oft damit)
	private static String clean(String s) {
		return s.replaceAll("[\\p{Co}\\p{Cf}\\p{Cc}]", "").replaceAll("\\s+", " ");
	}

	// Macht Sonderzeichen sichtbar, z.B. , damit man sie im Chat lesen kann
	private static String esc(String s) {
		StringBuilder sb = new StringBuilder();
		for (char ch : s.toCharArray()) {
			if (ch < 32 || ch > 126) sb.append(String.format("\\u%04X", (int) ch));
			else sb.append(ch);
		}
		return sb.toString();
	}
	private static final java.util.Map<String, String> lastByField = new java.util.HashMap<>();

	private static void pollGui(Minecraft mc) {
		if (mc.gui == null) return;
		try {
			if (compFields == null) {
				compFields = new java.util.ArrayList<>();
				for (java.lang.reflect.Field f : mc.gui.getClass().getDeclaredFields()) {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
					if (Component.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						compFields.add(f);
					} else if (f.getType() == int.class && f.getName().toLowerCase().contains("overlay")) {
						f.setAccessible(true);
						timeField = f;
					}
				}
			}
			int time = timeField != null ? timeField.getInt(mc.gui) : 0;
			for (java.lang.reflect.Field f : compFields) {
				Object v = f.get(mc.gui);
				if (!(v instanceof Component c)) continue;
				String raw = c.getString();
				String s = clean(raw);
				if (s.isEmpty()) continue;
				String prev = lastByField.put(f.getName(), s);
				if (debug && !s.equals(prev)) chat("[AutoFish] " + f.getName() + ": " + esc(raw));
				String low = s.toLowerCase();
				if (low.contains("da zieht was") || low.contains("sneak halten") || low.contains("loslassen")) {
					lastMiniTick = age;
				}
				Matcher m = TENSION.matcher(s);
				if (!m.find()) continue;
				lastMiniTick = age;
				boolean changed = !s.equals(lastText);
				boolean refreshed = timeField != null ? time > lastTime : false;
				tension = Integer.parseInt(m.group(1));
				if (changed || refreshed || timeField == null) lastTensionTick = age;
				lastText = s;
			}
			lastTime = time;
		} catch (Throwable t) {
			// still weitermachen, falls ein Feld nicht lesbar ist
		}
	}

	@Override
	public void onInitializeClient() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String raw = message.getString();
			String text = clean(raw);
			// Server meldet "warte bis der Fang abgeschlossen ist": Minispiel laeuft -> ca. 5 Sekunden Finger weg von der Angel
			if (text.toLowerCase().contains("abgeschlossen")) {
				lastMiniTick = age + 60;
			}
			if (debug && overlay) {
				chat("[AutoFish] Actionbar: " + esc(raw));
			}
			Matcher m = TENSION.matcher(text);
			if (m.find()) {
				tension = Integer.parseInt(m.group(1));
				lastTensionTick = age;
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(AutoFishClient::tick);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("autofish")
				.then(ClientCommands.literal("on").executes(c -> {
					enabled = true;
					c.getSource().sendFeedback(Component.literal("AutoFish AN"));
					return 1;
				}))
				.then(ClientCommands.literal("off").executes(c -> {
					enabled = false;
					releaseSneak();
					c.getSource().sendFeedback(Component.literal("AutoFish AUS"));
					return 1;
				}))
				.then(ClientCommands.literal("debug").executes(c -> {
					debug = !debug;
					out = msg -> c.getSource().sendFeedback(msg);
					c.getSource().sendFeedback(Component.literal("Debug: " + debug));
					return 1;
				}))
				.then(ClientCommands.literal("low")
					.then(ClientCommands.argument("wert", IntegerArgumentType.integer(0, 100)).executes(c -> {
						low = IntegerArgumentType.getInteger(c, "wert");
						c.getSource().sendFeedback(Component.literal("low = " + low));
						return 1;
					})))
				.then(ClientCommands.literal("high")
					.then(ClientCommands.argument("wert", IntegerArgumentType.integer(0, 100)).executes(c -> {
						high = IntegerArgumentType.getInteger(c, "wert");
						c.getSource().sendFeedback(Component.literal("high = " + high));
						return 1;
					})))
				.then(ClientCommands.literal("delay")
					.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 100)).executes(c -> {
						checkDelay = IntegerArgumentType.getInteger(c, "ticks");
						c.getSource().sendFeedback(Component.literal("delay = " + checkDelay + " Ticks"));
						return 1;
					})))
				.then(ClientCommands.literal("shift").executes(c -> {
					forceHold = 60;
					c.getSource().sendFeedback(Component.literal("Halte Shift fuer 3 Sekunden"));
					return 1;
				}))
				.then(ClientCommands.literal("fake")
					.then(ClientCommands.argument("wert", IntegerArgumentType.integer(0, 100)).executes(c -> {
						tension = IntegerArgumentType.getInteger(c, "wert");
						lastTensionTick = age + 40; // gilt ca. 3 Sekunden als frisch
						c.getSource().sendFeedback(Component.literal("Vorgetaeuschte Spannung: " + tension + "% (AutoFish muss AN sein)"));
						return 1;
					})))
				.then(ClientCommands.literal("status").executes(c -> {
					c.getSource().sendFeedback(Component.literal(
						"an=" + enabled + " low=" + low + " high=" + high + " delay=" + checkDelay + " spannung=" + tension));
					return 1;
				}))
			));
	}

	private static void tick(Minecraft mc) {
		age++;
		pollGui(mc);
		LocalPlayer p = mc.player;
		if (forceHold > 0) {
			forceHold--;
			mc.options.keyShift.setDown(forceHold > 0);
			return;
		}
		if (!enabled || p == null || mc.gameMode == null) {
			if (holding) releaseSneak();
			return;
		}

		boolean tensionFresh = tension >= 0 && age - lastTensionTick <= 20;
		boolean miniSeen = age - lastMiniTick <= 40;

		if (tensionFresh || miniSeen) {
			biteAt = -1;
			if (tensionFresh) {
				if (tension >= high) holding = false;
				else if (tension <= low) holding = true;
			} else {
				// Minispiel erkannt, aber keine Spannung lesbar: abwechselnd ziehen und nachlassen
				holding = (age / 10) % 2 == 0;
			}
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
		if (out != null) out.accept(Component.literal(s));
	}
}
