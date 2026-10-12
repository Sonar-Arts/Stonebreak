package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.masonry.MPainter;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Classes tab's state: the class shown, the sidebar's and the ability list's scroll, and the
 * rules its presses run (#301: the {@code stonebreak:screen.character-sheet} actions).
 *
 * <p>Left sidebar: scrollable list of all classes. Right area: the shown class's description and
 * its scrollable abilities.
 */
public class ClassesTab {

  private static final float CLASS_BTN_H      = 22f;
  private static final float CLASS_BTN_GAP    = 2f;
  /** The class list's visible height (logical px): the Talents sub-tab bar takes 32 px of the old 420. */
  public static final float SIDEBAR_CLIP_H   = 388f;
  private static final float ABILITY_ROW_H    = 64f;
  /** One Spend CP button per ability slot. */
  private static final int   MAX_ABILITIES    = 5;

  private float leftScrollOffset  = 0f;
  private float rightScrollOffset = 0f;
  private String displayedClassId = null;

  /** The ability list's clip height as last laid out; the scroll clamp's view height. */
  private float lastAbilityClipH = 365f;

  public String displayedClassId() { return displayedClassId; }
  public float leftScroll() { return leftScrollOffset; }
  public float rightScroll() { return rightScrollOffset; }

  /** A press on a class row: shows it, its ability list back at the top. */
  public void selectClass(String classId) {
    if (ClassRegistry.findById(classId).isPresent()) {
      displayedClassId = classId;
      rightScrollOffset = 0f;
    }
  }

  /** A press on the {@code index}-th Spend button: spends when a class is shown and CP remain. */
  public boolean spend(CharacterStats stats, int index) {
    if (displayedClassId == null || stats.getRemainingCp() <= 0 || index < 0 || index >= MAX_ABILITIES) {
      return false;
    }
    stats.spendCpOnAbility(displayedClassId + ":" + index);
    return true;
  }

  /** The ability list's clip height, for its scroll clamp. */
  public void abilityClipHeight(float h) { lastAbilityClipH = h; }

  /** The wheel over the sidebar ({@code sidebar}) or over the ability list. */
  public void scroll(float deltaY, boolean sidebar, float scale) {
    if (sidebar) {
      float totalListH = ClassRegistry.ALL.size() * ((CLASS_BTN_H + CLASS_BTN_GAP) * scale);
      float maxScroll = Math.max(0f, totalListH - SIDEBAR_CLIP_H * scale);
      leftScrollOffset = Math.clamp(leftScrollOffset + deltaY * 20f, 0f, maxScroll);
    } else {
      Optional<PlayerClassDefinition> cls = classOpt();
      if (cls.isPresent()) {
        float totalH = cls.get().abilities().size() * ABILITY_ROW_H * scale;
        float maxScroll = Math.max(0f, totalH - lastAbilityClipH);
        rightScrollOffset = Math.clamp(rightScrollOffset + deltaY * 20f, 0f, maxScroll);
      }
    }
  }

  /** Greedy word wrap of a class description to {@code maxWidth} pixels, honouring "\n" breaks. */
  public static List<String> wrap(Font font, String text, float maxWidth) {
    List<String> lines = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return lines;
    }
    for (String paragraph : text.split("\n")) {
      StringBuilder current = new StringBuilder();
      for (String word : paragraph.split(" ")) {
        String candidate = current.isEmpty() ? word : current + " " + word;
        if (current.length() > 0 && MPainter.measureWidth(font, candidate) > maxWidth) {
          lines.add(current.toString());
          current = new StringBuilder(word);
        } else {
          current = new StringBuilder(candidate);
        }
      }
      lines.add(current.toString());
    }
    return lines;
  }

  private Optional<PlayerClassDefinition> classOpt() {
    return displayedClassId != null
        ? ClassRegistry.findById(displayedClassId)
        : Optional.empty();
  }
}
