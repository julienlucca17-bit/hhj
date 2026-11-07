# AutoFish Spannung (Fabric, Minecraft 26.2)

Auto-Angeln + automatisches Shift-Halten/Loslassen im "Da zieht was!"-Minispiel.

## Bauen
Du brauchst **JDK 25**.

Am sichersten: Vorlage von https://fabricmc.net/develop/template/ erzeugen
(Minecraft 26.2, Mojang-Mappings, Package `de.autofish`), dann
`src/main/java/de/autofish/AutoFishClient.java` und `src/main/resources/fabric.mod.json`
durch meine Dateien ersetzen. Dann:

    ./gradlew build

Die fertige Mod liegt in `build/libs/autofish-1.0.0.jar` (nicht die `-sources.jar`).
Ins `mods`-Ordner kopieren, zusammen mit der Fabric API.

Dieses Projekt wurde NICHT kompiliert (kein Netz in meiner Umgebung). Falls der Build
einen Fehler zu einem Klassen- oder Methodennamen meldet, schick mir die Fehlermeldung.

## Befehle im Spiel
- `/autofish on` / `/autofish off`
- `/autofish debug`  zeigt jede Actionbar-Nachricht im Chat (zum Pruefen, ob die Spannung erkannt wird)
- `/autofish low 55`   unter diesem Wert wird Shift gehalten (ziehen)
- `/autofish high 80`  ab diesem Wert wird Shift losgelassen (Schnur geben)
- `/autofish delay 10` Ticks nach dem Biss, bevor normal eingeholt wird
- `/autofish status`

## Einstellen
1. Angel in die Haupthand, `/autofish debug`, einmal angeln bis ein Special Fish kommt.
2. Siehst du `NN% Spannung` im Chat, wird es erkannt. Wenn nicht, schick mir die Zeile.
3. Bei welcher Spannung reisst die Schnur? Dann `high` ein Stueck darunter setzen.
