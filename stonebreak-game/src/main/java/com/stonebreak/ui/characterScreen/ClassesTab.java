package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.masonry.MPainter;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.classes.ClassRegistry;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * The Classes tab's state: the class shown, the sidebar's and the talent tree's scroll, and the
 * rules its presses run (#301: the {@code stonebreak:screen.character-sheet} actions).
 *
 * <p>Left sidebar: scrollable list of all classes, with the ranks invested in each. Right area:
 * the shown class's description and its scrollable talent tree (core, then three paths).
 */
public class ClassesTab {

  private static final float CLASS_BTN_H      = 22f;
  private static final float CLASS_BTN_GAP    = 2f;
  /** The class list's visible height (logical px): the Talents sub-tab bar takes 32 px of the old 420. */
  public static final float SIDEBAR_CLIP_H   = 388f;

  private float leftScrollOffset  = 0f;
  private float rightScrollOffset = 0f;
  private String displayedClassId = null;

  /** The tree's clip height and content height as last laid out; the scroll clamp's extent. */
  private float lastAbilityClipH = 365f;
  private float lastTreeContentH = 0f;

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

  /** A press on a tree node's + button: one rank, if the node belongs to the class shown and can take it. */
  public boolean addRank(CharacterStats stats, String key) {
    if (displayedClassId == null || !displayedClassId.equals(ClassRegistry.classIdOf(key))) {
      return false;
    }
    return stats.addRank(key);
  }

  /** The tree's clip height, for its scroll clamp. */
  public void abilityClipHeight(float h) { lastAbilityClipH = h; }

  /** The tree's laid-out content height (rows differ in height), for its scroll clamp. */
  public void treeContentHeight(float h) { lastTreeContentH = h; }

  /** The wheel over the sidebar ({@code sidebar}) or over the ability list. */
  public void scroll(float deltaY, boolean sidebar, float scale) {
    if (sidebar) {
      float totalListH = ClassRegistry.ALL.size() * ((CLASS_BTN_H + CLASS_BTN_GAP) * scale);
      float maxScroll = Math.max(0f, totalListH - SIDEBAR_CLIP_H * scale);
      leftScrollOffset = Math.clamp(leftScrollOffset + deltaY * 20f, 0f, maxScroll);
    } else {
      if (displayedClassId != null) {
        float maxScroll = Math.max(0f, lastTreeContentH - lastAbilityClipH);
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
}
