package com.blazemeter.jmeter.http2.control.gui;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.event.ItemEvent;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.apache.jmeter.control.gui.AbstractControllerGui;
import org.apache.jmeter.gui.util.HorizontalPanel;
import org.apache.jmeter.testelement.TestElement;

public class HTTP2ControllerGUI extends AbstractControllerGui {
  private static final long serialVersionUID = 240L;
  private final JCheckBox generateControllerSample;
  private final JCheckBox limitMaxParallel;
  private final JTextField maxParallelField;
  private int defaultMaxParallel;

  public HTTP2ControllerGUI() {
    generateControllerSample = new JCheckBox("Generate Parent Sample");
    limitMaxParallel = new JCheckBox("Limit max number of parallel executions");
    maxParallelField = new JTextField(8);
    init();
  }

  @Override
  public String getStaticLabel() {
    return "bzm - HTTP Async Controller";
  }

  @Override
  public TestElement createTestElement() {
    HTTP2Controller lc = new HTTP2Controller();
    configureTestElement(lc);
    return lc;
  }

  @Override
  public void modifyTestElement(TestElement el) {
    configureTestElement(el);
    if (el instanceof HTTP2Controller) {
      HTTP2Controller controller = (HTTP2Controller) el;
      controller.setGenerateControllerSample(generateControllerSample.isSelected());
      controller.setLimitMaxParallel(limitMaxParallel.isSelected());
      if (limitMaxParallel.isSelected()) {
        controller.setMaxConcurrentAsyncInController(parseMaxParallelValue());
      }
    }
  }

  @Override
  public void configure(TestElement element) {
    super.configure(element);
    if (element instanceof HTTP2Controller) {
      HTTP2Controller controller = (HTTP2Controller) element;
      generateControllerSample.setSelected(controller.isGenerateControllerSample());
      limitMaxParallel.setSelected(controller.isLimitMaxParallel());
      maxParallelField.setText(String.valueOf(controller.getMaxConcurrentAsyncInController()));
      updateMaxParallelFieldState(controller);
    }
  }

  @Override
  public String getLabelResource() {
    return null;
  }

  private void init() {
    // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
    setLayout(new BorderLayout(0, 5));
    setBorder(makeBorder());
    Container topPanel = makeTitlePanel();
    add(topPanel, BorderLayout.NORTH);
    buildOptionsPanel(topPanel);
  }

  private void buildOptionsPanel(Container topPanel) {
    JLabel disclaimer = new JLabel(
        "All direct child elements of this controller that can run in HTTP Async "
            + "will run in parallel.", JLabel.CENTER);
    topPanel.add(disclaimer);

    JPanel parentSamplePanel = new HorizontalPanel();
    parentSamplePanel.add(generateControllerSample);
    parentSamplePanel.add(new JLabel("Generate parent sample", JLabel.RIGHT));

    JPanel limitPanel = new HorizontalPanel();
    limitPanel.add(limitMaxParallel);
    limitPanel.add(new JLabel("Limit max number of parallel executions", JLabel.RIGHT));

    JPanel maxPanel = new HorizontalPanel();
    maxPanel.add(new JLabel("Max parallel: ", JLabel.RIGHT));
    maxPanel.add(maxParallelField);

    HorizontalPanel limitWrap = new HorizontalPanel();
    limitWrap.add(limitPanel);
    limitWrap.add(maxPanel);

    topPanel.add(parentSamplePanel);
    topPanel.add(limitWrap);

    limitMaxParallel.addItemListener(event -> {
      boolean enabled = event.getStateChange() == ItemEvent.SELECTED;
      updateMaxParallelFieldState(enabled);
      if (!enabled && defaultMaxParallel > 0) {
        maxParallelField.setText(String.valueOf(defaultMaxParallel));
      }
    });
  }

  private void updateMaxParallelFieldState(HTTP2Controller controller) {
    defaultMaxParallel = controller.getDefaultMaxConcurrentAsyncInController();
    updateMaxParallelFieldState(controller.isLimitMaxParallel());
    if (!controller.isLimitMaxParallel()) {
      maxParallelField.setText(
          String.valueOf(defaultMaxParallel));
    }
  }

  private void updateMaxParallelFieldState(boolean enabled) {
    maxParallelField.setEnabled(enabled);
    maxParallelField.setEditable(enabled);
  }

  private int parseMaxParallelValue() {
    try {
      int value = Integer.parseInt(maxParallelField.getText().trim());
      return Math.max(1, value);
    } catch (Exception e) {
      return defaultMaxParallel > 0 ? defaultMaxParallel : 1;
    }
  }
}
