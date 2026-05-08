package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.sampler.HttpSamplerToBlazeMeterHttpMigrator;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a migrate item after JMeter builds the test plan {@link JTree} popup.
 *
 * <p>Mirrors Tools migrate-selected for migratable HTTP samplers.
 *
 * <p>{@link org.apache.jmeter.gui.plugin.MenuCreator} has no tree popup slot;
 * attaches a {@link JTree} listener only (no reflection).
 *
 * <p>JMeter shows the tree menu via {@code SwingUtilities.invokeLater} from
 * {@link org.apache.jmeter.gui.tree.JMeterTreeListener}; we defer augmentation
 * twice on the EDT so our code runs after that popup is visible.
 */
public final class HttpRequestMigrateTreePopupSupport {

  /** Class logger. */
  private static final Logger LOG =
      LoggerFactory.getLogger(HttpRequestMigrateTreePopupSupport.class);

  /** Grep-friendly tag for install diagnostics. */
  private static final String LOG_PREFIX = "[bzm tree migrate popup] ";

  /** Ensures mouse hook is registered at most once per GUI session. */
  private static final AtomicBoolean TREE_HOOK_INSTALLED =
      new AtomicBoolean(false);

  /**
   * {@link org.apache.jmeter.gui.tree.JMeterTreeListener#setJTree} may run
   * after the first {@link #installHookOnce()} attempt from the plugin static
   * block; retry on the EDT until the test-plan tree exists.
   */
  private static final AtomicInteger TREE_INSTALL_ATTEMPTS = new AtomicInteger(0);

  private static final int MAX_TREE_INSTALL_ATTEMPTS = 600;

  /**
   * Visible label; command id from
   * {@link BlazeMeterHttpMenuCommand#migrateSelectedActionCommand()}.
   */
  private static final String CONTEXT_LABEL =
      "Migrate to BlazeMeter HTTP Sampler…";

  /** Hidden ctor for static helpers. */
  private HttpRequestMigrateTreePopupSupport() {
    // Hidden.
  }

  /**
   * Registers a {@link MouseAdapter} once; defers augmentation on right-click
   * until after JMeter's deferred {@code displayPopUp}.
   */
  public static void installHookOnce() {
    if (!TREE_HOOK_INSTALLED.compareAndSet(false, true)) {
      return;
    }
    GuiPackage gp = GuiPackage.getInstance();
    JMeterTreeListener listener =
        gp == null ? null : gp.getTreeListener();
    JTree tree = listener == null ? null : listener.getJTree();
    if (tree == null) {
      int attempt = TREE_INSTALL_ATTEMPTS.incrementAndGet();
      TREE_HOOK_INSTALLED.set(false);
      if (attempt == 1) {
        LOG.warn(
            LOG_PREFIX.concat(
                "JTree not ready; will retry installing migrate popup hook on EDT."));
      }
      if (attempt <= MAX_TREE_INSTALL_ATTEMPTS) {
        SwingUtilities.invokeLater(HttpRequestMigrateTreePopupSupport::installHookOnce);
      } else {
        LOG.error(
            LOG_PREFIX.concat(
                "mouse hook not installed: JTree still null after "
                    + MAX_TREE_INSTALL_ATTEMPTS
                    + " EDT attempts."));
      }
      return;
    }
    TREE_INSTALL_ATTEMPTS.set(0);
    tree.addMouseListener(newPopupAugmentMouseListener(tree));
    LOG.info(LOG_PREFIX.concat("migrate item hook installed on test plan JTree."));
  }

  /**
   * Listener that schedules menu augmentation on popup gesture.
   *
   * @param tree test plan tree (JMeter main tree).
   * @return mouse listener to register on {@code tree}.
   */
  private static MouseAdapter newPopupAugmentMouseListener(final JTree tree) {
    return new MouseAdapter() {
      @Override
      public void mousePressed(final MouseEvent e) {
        requestAugment(tree, e);
      }

      @Override
      public void mouseReleased(final MouseEvent e) {
        requestAugment(tree, e);
      }
    };
  }

  /**
   * Schedules {@code augmentTreePopup} after mouse handling settles.
   *
   * @param tree JMeter test plan tree.
   * @param e AWT mouse event (popup gesture or right button).
   */
  private static void requestAugment(final JTree tree, final MouseEvent e) {
    if (!e.isPopupTrigger() && !SwingUtilities.isRightMouseButton(e)) {
      return;
    }
    /*
     * JMeterTreeListener queues GuiPackage#displayPopUp via invokeLater; two
     * nested invokeLater calls here run after that showing pass on the EDT.
     */
    SwingUtilities.invokeLater(
        () ->
            SwingUtilities.invokeLater(() -> augmentTreePopup(tree)));
  }

  /**
   * Adds a migrate entry only when popup and migratable HTTP selection match.
   *
   * @param tree JMeter tree that showed the popup.
   */
  private static void augmentTreePopup(final JTree tree) {
    JPopupMenu popup = currentTreePopup(tree);
    if (popup == null) {
      return;
    }
    if (migrateMenuItemAlreadyPresent(popup)) {
      return;
    }
    if (!selectionHasMigratableHttpSampler(tree)) {
      return;
    }
    popup.addSeparator();
    popup.add(createMigrateMenuItem());
  }

  /**
   * @param tree unused; selection via {@link GuiPackage}.
   * @return true if selection holds a migratable Apache HTTP sampler.
   */
  private static boolean selectionHasMigratableHttpSampler(final JTree tree) {
    GuiPackage gp = GuiPackage.getInstance();
    if (gp == null || gp.getTreeListener() == null) {
      return false;
    }
    JMeterTreeNode[] sel = gp.getTreeListener().getSelectedNodes();
    if (sel == null) {
      return false;
    }
    for (JMeterTreeNode n : sel) {
      if (n == null) {
        continue;
      }
      if (HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(
          n.getTestElement())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves visible tree popup for this invoker.
   *
   * @param tree JMeter test plan tree.
   * @return popup when {@link MenuSelectionManager} path matches, else null.
   */
  private static JPopupMenu currentTreePopup(final JTree tree) {
    MenuElement[] path =
        MenuSelectionManager.defaultManager().getSelectedPath();
    if (path == null) {
      return null;
    }
    for (MenuElement me : path) {
      if (me instanceof JPopupMenu) {
        JPopupMenu p = (JPopupMenu) me;
        Component invoker = p.getInvoker();
        if (invoker == tree || isTreeOrTreeAncestor(invoker, tree)) {
          return p;
        }
      }
    }
    return null;
  }

  private static boolean isTreeOrTreeAncestor(
      final Component invoker, final JTree tree) {
    if (invoker == tree) {
      return true;
    }
    if (invoker instanceof Container) {
      return SwingUtilities.isDescendingFrom(tree, invoker);
    }
    return false;
  }

  /**
   * @param popup JMeter context menu currently shown.
   * @return true if our migrate item is already mounted.
   */
  private static boolean migrateMenuItemAlreadyPresent(final JPopupMenu popup) {
    for (Component c : popup.getComponents()) {
      if (c instanceof JMenuItem
          && BlazeMeterHttpMenuCommand.migrateSelectedActionCommand().equals(
              ((JMenuItem) c).getActionCommand())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds menu item wired to migrate-selected routing.
   *
   * @return item for the tree context menu.
   */
  private static JMenuItem createMigrateMenuItem() {
    JMenuItem migrate = new JMenuItem(CONTEXT_LABEL);
    String cmd = BlazeMeterHttpMenuCommand.migrateSelectedActionCommand();
    migrate.setName(cmd);
    migrate.setActionCommand(cmd);
    migrate.addActionListener(ActionRouter.getInstance());
    return migrate;
  }
}
