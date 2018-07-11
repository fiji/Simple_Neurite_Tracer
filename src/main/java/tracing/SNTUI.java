/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2018 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package tracing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import net.imagej.Dataset;
import net.imagej.table.DefaultGenericTable;

import org.scijava.command.CommandService;
import org.scijava.util.ClassUtils;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.HTMLDialog;
import ij.gui.StackWindow;
import ij.measure.Calibration;
import ij3d.Content;
import ij3d.ContentConstants;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import sholl.Sholl_Analysis;
import tracing.analysis.TreeAnalyzer;
import tracing.gui.ColorChangedListener;
import tracing.gui.ColorChooserButton;
import tracing.gui.GuiUtils;
import tracing.gui.SigmaPalette;
import tracing.hyperpanes.MultiDThreePanes;
import tracing.plugin.PlotterCmd;
import tracing.plugin.StrahlerCmd;

@SuppressWarnings("serial")
public class SNTUI extends JDialog {

	public static final boolean verbose = SNT.isDebugMode();

	/* Deprecated stuff to be removed soon */
	@Deprecated
	private final String noColorImageString = "[None]";
	@Deprecated
	private ImagePlus currentColorImage;
	@Deprecated
	private JComboBox<String> colorImageChoice;

	/* UI */
	private JComboBox<String> filterChoice;
	private JRadioButton showPathsSelected;
	private JRadioButton showPathsAll;
	protected JRadioButton showPartsNearby;
	protected JRadioButton showPartsAll;
	protected JCheckBox useSnapWindow;
	protected JSpinner snapWindowXYsizeSpinner;
	protected JSpinner snapWindowZsizeSpinner;
	private JSpinner nearbyFieldSpinner;
	private JButton showOrHidePathList;
	private JButton showOrHideFillList = new JButton(); // must be initialized
	private JPanel hessianPanel;
	private JCheckBox preprocess;
	private JButton displayFiltered;
	private JMenuItem loadTracesMenuItem;
	private JMenuItem loadSWCMenuItem;
	private JMenuItem loadLabelsMenuItem;
	private JMenuItem saveMenuItem;
	private JMenuItem exportCSVMenuItem;
	private JMenuItem exportAllSWCMenuItem;
	private JMenuItem quitMenuItem;
	private JMenuItem measureMenuItem;
	private JMenuItem strahlerMenuItem;
	private JMenuItem plotMenuItem;
	private JMenuItem sendToTrakEM2;
	private JLabel statusText;
	private JLabel statusBarText;
	private JButton keepSegment;
	private JButton junkSegment;
	protected JButton abortButton;
	private JButton completePath;

	// UI controls for loading 'filtered image'
	private JPanel filteredImgPanel;
	private JTextField filteredImgPathField;
	private JButton filteredImgInitButton;
	private JButton filteredImgLoadButton;
	private JComboBox<String> filteredImgParserChoice;
	private final List<String> filteredImgAllowedExts = Arrays.asList("tif",
		"nrrd");
	private JCheckBox filteredImgActivateCheckbox;
	private SwingWorker<?, ?> filteredImgLoadingWorker;

	private static final int MARGIN = 4;
	private volatile int currentState;
	private volatile double currentSigma;
	private volatile double currentMultiplier;
	private volatile boolean ignoreColorImageChoiceEvents = false;
	private volatile boolean ignorePreprocessEvents = false;
	private volatile int preGaussianState;

	private final SimpleNeuriteTracer plugin;
	private final PathAndFillManager pathAndFillManager;
	protected final GuiUtils guiUtils;
	private final PathManagerUI pmUI;
	private final FillManagerUI fmUI;
	protected final GuiListener listener;

	/* These are the states that the UI can be in: */
	/** The flag specifying 'ready' mode */
	public static final int WAITING_TO_START_PATH = 0;
	static final int PARTIAL_PATH = 1;
	static final int SEARCHING = 2;
	static final int QUERY_KEEP = 3;
	// static final int LOGGING_POINTS = 4;
	// static final int DISPLAY_EVS = 5;
	static final int FILLING_PATHS = 6;
	static final int CALCULATING_GAUSSIAN = 7;
	static final int WAITING_FOR_SIGMA_POINT = 8;
	static final int WAITING_FOR_SIGMA_CHOICE = 9;
	static final int SAVING = 10;
	static final int LOADING = 11;
	/** The flag specifying 'fitting' mode */
	public static final int FITTING_PATHS = 12;
	static final int LOADING_FILTERED_IMAGE = 13;
	/** The flag specifying 'editing' mode */
	public static final int EDITING_MODE = 14;
	/** The flag specifying 'paused' mode */
	public static final int PAUSED = 15;
	/** The flag specifying 'analysis' mode */
	public static final int ANALYSIS_MODE = 16;
	static final int IMAGE_CLOSED = -1;

	// TODO: Internal preferences: should be migrated to SNTPrefs
	protected boolean confirmTemporarySegments = true;
	protected boolean finishOnDoubleConfimation = true;
	protected boolean discardOnDoubleCancellation = true;

	/**
	 * Instantiates SNT's main UI and associated {@link PathManagerUI} and
	 * {@link FillManagerUI} instances.
	 *
	 * @param plugin
	 *            the {@link SimpleNeuriteTracer} instance associated with this UI
	 */
	public SNTUI(final SimpleNeuriteTracer plugin) {

		super(plugin.legacyService.getIJ1Helper().getIJ(), "SNT v" + SNT.VERSION,
			false);
		guiUtils = new GuiUtils(this);
		this.plugin = plugin;
		new ClarifyingKeyListener(plugin).addKeyAndContainerListenerRecursively(
			this);
		listener = new GuiListener();

		assert SwingUtilities.isEventDispatchThread();

		pathAndFillManager = plugin.getPathAndFillManager();
		addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				exitRequested();
			}
		});

		final JTabbedPane tabbedPane = new JTabbedPane();

		{ // Main tab
			final GridBagConstraints c1 = GuiUtils.defaultGbc();
			if (!plugin.nonInteractiveSession) {
				final JPanel tab1 = getTab();
				// c.insets.left = MARGIN * 2;
				c1.anchor = GridBagConstraints.NORTHEAST;
				GuiUtils.addSeparator(tab1, "Cursor Auto-snapping:", false, c1);
				++c1.gridy;
				tab1.add(snappingPanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "Auto-tracing:", true, c1);
				++c1.gridy;
				tab1.add(autoTracingPanel(), c1);
				++c1.gridy;
				tab1.add(filteredImagePanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "Path Rendering:", true, c1);
				++c1.gridy;
				tab1.add(renderingPanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "Path Labelling:", true, c1);
				++c1.gridy;
				tab1.add(colorOptionsPanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "", true, c1); // empty separator
				++c1.gridy;
				c1.fill = GridBagConstraints.HORIZONTAL;
				c1.insets = new Insets(0, 0, 0, 0);
				tab1.add(hideWindowsPanel(), c1);
				tabbedPane.addTab(" Main ", tab1);
			}
		}

		{ // Options Tab
			final JPanel tab2 = getTab();
			tab2.setLayout(new GridBagLayout());
			final GridBagConstraints c2 = GuiUtils.defaultGbc();
			// c2.insets.left = MARGIN * 2;
			c2.anchor = GridBagConstraints.NORTHEAST;
			c2.gridwidth = GridBagConstraints.REMAINDER;
			if (!plugin.nonInteractiveSession) {
				GuiUtils.addSeparator(tab2, "Data Source:", false, c2);
				++c2.gridy;
				tab2.add(sourcePanel(), c2);
				++c2.gridy;
			}
			else {
				GuiUtils.addSeparator(tab2, "Path Rendering:", false, c2);
				++c2.gridy;
				tab2.add(renderingPanel(), c2);
				++c2.gridy;
				GuiUtils.addSeparator(tab2, "Path Labelling:", true, c2);
				++c2.gridy;
				tab2.add(colorOptionsPanel(), c2);
				++c2.gridy;
			}

			GuiUtils.addSeparator(tab2, "Views:", true, c2);
			++c2.gridy;
			tab2.add(viewsPanel(), c2);
			++c2.gridy;
			if (!plugin.nonInteractiveSession) {
				GuiUtils.addSeparator(tab2, "Temporary Paths:", true, c2);
				++c2.gridy;
				tab2.add(tracingPanel(), c2);
				++c2.gridy;
			}
			GuiUtils.addSeparator(tab2, "UI Interaction:", true, c2);
			++c2.gridy;
			tab2.add(interactionPanel(), c2);
			++c2.gridy;
			GuiUtils.addSeparator(tab2, "Misc:", true, c2);
			++c2.gridy;
			c2.weighty = 1;
			tab2.add(miscPanel(), c2);
			tabbedPane.addTab(" Options ", tab2);
		}

		{ // 3D tab
			final JPanel tab3 = getTab();
			tab3.setLayout(new GridBagLayout());
			final GridBagConstraints c3 = GuiUtils.defaultGbc();
			// c3.insets.left = MARGIN * 2;
			c3.anchor = GridBagConstraints.NORTHEAST;
			c3.gridwidth = GridBagConstraints.REMAINDER;
			GuiUtils.addSeparator(tab3, "Legacy 3D Viewer:", false, c3);
			++c3.gridy;
			// if (!plugin.nonInteractiveSession) {
			// GuiUtils.addSeparator(tab2, "Data Source:", false, c2);
			// ++c2.gridy;
			// tab2.add(sourcePanel(), c2);
			// ++c2.gridy;
			// } else {
			// GuiUtils.addSeparator(tab2, "Path Rendering:", false, c2);
			// ++c2.gridy;
			// tab2.add(renderingPanel(), c2);
			// ++c2.gridy;
			// GuiUtils.addSeparator(tab2, "Path Labelling:", true, c2);
			// ++c2.gridy;
			// tab2.add(colorOptionsPanel(), c2);
			// ++c2.gridy;
			// }
			tabbedPane.addTab(" 3D ", tab3);
			tab3.add(legacy3DViewerPanel(), c3);

		}

		tabbedPane.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				if (tabbedPane.getSelectedIndex() == 1 &&
					getCurrentState() > WAITING_TO_START_PATH &&
					getCurrentState() < EDITING_MODE)
				{
					tabbedPane.setSelectedIndex(0);
					guiUtils.blinkingError(statusText,
						"Please complete current task before selecting the \"Options\" tab.");
				}
			}
		});
		setJMenuBar(createMenuBar());
		setLayout(new GridBagLayout());
		final GridBagConstraints dialogGbc = GuiUtils.defaultGbc();
		add(statusPanel(), dialogGbc);
		dialogGbc.gridy++;
		add(tabbedPane, dialogGbc);
		dialogGbc.gridy++;
		add(statusBar(), dialogGbc);
		pack();
		toFront();

		pmUI = new PathManagerUI(plugin);
		pmUI.setLocation(getX() + getWidth(), getY());

		fmUI = new FillManagerUI(plugin);
		fmUI.setLocation(getX() + getWidth(), getY() + pmUI.getHeight());

		changeState(WAITING_TO_START_PATH);
	}

	/**
	 * Gets the current UI state.
	 *
	 * @return the current UI state, e.g., {@link SNTUI#WAITING_FOR_SIGMA_POINT},
	 *         {@link SNTUI#WAITING_FOR_SIGMA_POINT}, etc.
	 */
	public int getCurrentState() {
		return currentState;
	}

	private void updateStatusText(final String newStatus,
		final boolean includeStatusBar)
	{
		updateStatusText(newStatus);
		if (includeStatusBar) showStatus(newStatus, true);
	}

	private void updateStatusText(final String newStatus) {
		statusText.setText("<html><strong>" + newStatus + "</strong></html>");
	}

	@Deprecated
	synchronized protected void updateColorImageChoice() {
		assert SwingUtilities.isEventDispatchThread();

		ignoreColorImageChoiceEvents = true;

		// Try to preserve the old selection:
		final String oldSelection = (String) colorImageChoice.getSelectedItem();

		colorImageChoice.removeAllItems();

		int j = 0;
		colorImageChoice.addItem(noColorImageString);

		int selectedIndex = 0;

		final int[] wList = WindowManager.getIDList();
		if (wList != null) {
			for (int i = 0; i < wList.length; i++) {
				final ImagePlus imp = WindowManager.getImage(wList[i]);
				j++;
				final String title = imp.getTitle();
				colorImageChoice.addItem(title);
				if (title == oldSelection) selectedIndex = j;
			}
		}

		colorImageChoice.setSelectedIndex(selectedIndex);

		ignoreColorImageChoiceEvents = false;

		// This doesn't trigger an item event
		checkForColorImageChange();
	}

	@Deprecated
	synchronized protected void checkForColorImageChange() {
		final String selectedTitle = (String) colorImageChoice.getSelectedItem();

		ImagePlus intendedColorImage = null;
		if (selectedTitle != null && !selectedTitle.equals(noColorImageString)) {
			intendedColorImage = WindowManager.getImage(selectedTitle);
		}

		if (intendedColorImage != currentColorImage) {
			if (intendedColorImage != null) {
				final ImagePlus image = plugin.getImagePlus();
				final Calibration calibration = plugin.getImagePlus().getCalibration();
				final Calibration colorImageCalibration = intendedColorImage
					.getCalibration();
				if (!SNT.similarCalibrations(calibration, colorImageCalibration)) {
					guiUtils.centeredMsg("The calibration of '" + intendedColorImage
						.getTitle() + "' is different from the image you're tracing ('" +
						image.getTitle() + "')'\nThis may produce unexpected results.",
						"Warning");
				}
				if (!(intendedColorImage.getWidth() == image.getWidth() &&
					intendedColorImage.getHeight() == image.getHeight() &&
					intendedColorImage.getStackSize() == image.getStackSize())) guiUtils
						.centeredMsg("the dimensions (in voxels) of '" + intendedColorImage
							.getTitle() + "' is different from the image you're tracing ('" +
							image.getTitle() + "')'\nThis may produce unexpected results.",
							"Warning");

			}
			currentColorImage = intendedColorImage;
			plugin.setColorImage(currentColorImage);
		}
	}

	protected void gaussianCalculated(final boolean succeeded) {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				if (!succeeded) {
					ignorePreprocessEvents = true;
					preprocess.setSelected(false);
					ignorePreprocessEvents = false;
				}
				changeState(preGaussianState);
			}
		});
	}

	/**
	 * Sets the multiplier value for Hessian computation of curvatures updating the
	 * Hessian panel accordingly
	 *
	 * @param multiplier
	 *            the new multiplier value
	 */
	public void setMultiplier(final double multiplier) {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				currentMultiplier = multiplier;
				updateHessianLabel();
			}
		});
	}

	/**
	 * Sets the sigma value for computation of curvatures, updating the Hessian
	 * panel accordingly
	 *
	 * @param sigma
	 *            the new sigma value
	 * @param mayStartGaussian
	 *            if true and the current UI state allows it, the Gaussian
	 *            computation will be performed using the the new parameter
	 */
	public void setSigma(final double sigma, final boolean mayStartGaussian) {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				currentSigma = sigma;
				updateHessianLabel();
				if (!mayStartGaussian) return;
				preprocess.setSelected(false);

				// Turn on the checkbox: according to the documentation this doesn't
				// generate an event, so we manually turn on the Gaussian calculation
				ignorePreprocessEvents = true;
				preprocess.setSelected(true);
				ignorePreprocessEvents = false;
				enableHessian(true);
			}
		});
	}

	private void updateHessianLabel() {
		final String label = hotKeyLabel("Hessian-based analysis (\u03C3 = " + SNT
			.formatDouble(currentSigma, 2) + "; \u00D7 = " + SNT.formatDouble(
				currentMultiplier, 2) + ")", "H");
		assert SwingUtilities.isEventDispatchThread();
		preprocess.setText(label);
	}

	/**
	 * Gets the current Sigma value from the Hessian panel
	 *
	 * @return the sigma value currently in use
	 */
	public double getSigma() {
		return currentSigma;
	}

	/**
	 * Gets the current multiplier value from the Hessian panel
	 *
	 * @return the multiplier value currently in use
	 */
	public double getMultiplier() {
		return currentMultiplier;
	}

	protected void exitRequested() {
		assert SwingUtilities.isEventDispatchThread();
		if (plugin.pathsUnsaved() && !guiUtils.getConfirmation(
			"There are unsaved paths. Do you really want to quit?", "Really quit?"))
			return;
		if (pmUI.measurementsUnsaved() && !guiUtils.getConfirmation(
			"There are unsaved measurements. Do you really want to quit?",
			"Really quit?")) return;
		plugin.cancelSearch(true);
		plugin.notifyListeners(new SNTEvent(SNTEvent.QUIT));
		plugin.prefs.savePluginPrefs(true);
		pmUI.dispose();
		pmUI.closeTable();
		fmUI.dispose();
		dispose();
		plugin.closeAndResetAllPanes();
		SNT.setPlugin(null);
	}

	private void setEnableAutoTracingComponents(final boolean enable) {
		if (hessianPanel != null) GuiUtils.enableComponents(hessianPanel, enable);
		if (filteredImgPanel != null) GuiUtils.enableComponents(filteredImgPanel,
			enable);
		if (enable) updateFilteredFileField();
	}

	protected void disableImageDependentComponents() {
		assert SwingUtilities.isEventDispatchThread();
		loadLabelsMenuItem.setEnabled(false);
		fmUI.setEnabledNone();
		setEnableAutoTracingComponents(false);
		// GuiUtils.enableComponents(colorPanel, false);
	}

	private void disableEverything() {
		assert SwingUtilities.isEventDispatchThread();
		disableImageDependentComponents();
		abortButton.setEnabled(getState() != WAITING_TO_START_PATH);
		loadTracesMenuItem.setEnabled(false);
		loadSWCMenuItem.setEnabled(false);
		exportCSVMenuItem.setEnabled(false);
		exportAllSWCMenuItem.setEnabled(false);
		measureMenuItem.setEnabled(false);
		sendToTrakEM2.setEnabled(false);
		saveMenuItem.setEnabled(false);
		quitMenuItem.setEnabled(false);
	}

	
	/**
	 * Changes this UI to a new state.
	 *
	 * @param newState
	 *            the new state, e.g., {@link SNTUI#WAITING_TO_START_PATH},
	 *            {@link SNTUI#ANALYSIS_MODE}, etc.
	 */
	public void changeState(final int newState) {

		SNT.log("Changing state to: " + getState(newState));
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				switch (newState) {

					case WAITING_TO_START_PATH:

						keepSegment.setEnabled(false);
						junkSegment.setEnabled(false);
						completePath.setEnabled(false);
						abortButton.setEnabled(false);

						pmUI.valueChanged(null); // Fake a selection change in the path tree:
						showPartsNearby.setEnabled(isStackAvailable());
						setEnableAutoTracingComponents(plugin.isAstarEnabled());
						fmUI.setEnabledWhileNotFilling();
						loadLabelsMenuItem.setEnabled(true);
						saveMenuItem.setEnabled(true);
						loadTracesMenuItem.setEnabled(true);
						loadSWCMenuItem.setEnabled(true);

						exportCSVMenuItem.setEnabled(true);
						exportAllSWCMenuItem.setEnabled(true);
						measureMenuItem.setEnabled(true);
						sendToTrakEM2.setEnabled(plugin.anyListeners());
						quitMenuItem.setEnabled(true);
						showPathsSelected.setEnabled(true);

						if (plugin.nonInteractiveSession) {
							changeState(ANALYSIS_MODE);
						}
						else {
							showOrHideFillList.setEnabled(true); // not initialized in
																										// "Analysis Mode"
							updateStatusText("Click somewhere to start a new path...");
						}
						break;

					case PARTIAL_PATH:
						updateStatusText("Select a point further along the structure...");
						disableEverything();
						keepSegment.setEnabled(false);
						junkSegment.setEnabled(false);
						completePath.setEnabled(true);
						showPartsNearby.setEnabled(isStackAvailable());
						setEnableAutoTracingComponents(plugin.isAstarEnabled());
						quitMenuItem.setEnabled(false);
						break;

					case SEARCHING:
						updateStatusText("Searching for path between points...");
						disableEverything();
						break;

					case QUERY_KEEP:
						updateStatusText("Keep this new path segment?");
						disableEverything();
						keepSegment.setEnabled(true);
						junkSegment.setEnabled(true);
						break;

					case FILLING_PATHS:
						updateStatusText("Filling out selected paths...");
						disableEverything();
						fmUI.setEnabledWhileFilling();
						break;

					case FITTING_PATHS:
						updateStatusText("Fitting volumes around selected paths...");
						abortButton.setEnabled(true);
						break;

					case CALCULATING_GAUSSIAN:
						updateStatusText("Calculating Gaussian...");
						disableEverything();
						break;

					case LOADING_FILTERED_IMAGE:
						updateStatusText("Loading Filtered Image...");
						disableEverything();
						break;

					case WAITING_FOR_SIGMA_POINT:
						updateStatusText("Click on a representative structure...");
						disableEverything();
						break;

					case WAITING_FOR_SIGMA_CHOICE:
						updateStatusText("Close the sigma palette window to continue...");
						disableEverything();
						break;

					case LOADING:
						updateStatusText("Loading...");
						disableEverything();
						break;

					case SAVING:
						updateStatusText("Saving...");
						disableEverything();
						break;

					case EDITING_MODE:
						if (noPathsError()) return;
						plugin.setCanvasLabelAllPanes(InteractiveTracerCanvas.EDIT_MODE_LABEL);
						updateStatusText("Editing Mode. Tracing functions disabled...");
						disableEverything();
						keepSegment.setEnabled(false);
						junkSegment.setEnabled(false);
						completePath.setEnabled(false);
						showPartsNearby.setEnabled(isStackAvailable());
						setEnableAutoTracingComponents(false);
						getFillManager().setVisible(false);
						showOrHideFillList.setEnabled(false);
						break;

					case PAUSED:
						updateStatusText("SNT is paused. Tracing functions disabled...");
						disableEverything();
						keepSegment.setEnabled(false);
						junkSegment.setEnabled(false);
						abortButton.setEnabled(true);
						completePath.setEnabled(false);
						showPartsNearby.setEnabled(isStackAvailable());
						setEnableAutoTracingComponents(false);
						getFillManager().setVisible(false);
						showOrHideFillList.setEnabled(false);
						break;
					case ANALYSIS_MODE:
						updateStatusText("Analysis mode. Tracing disabled...");
						plugin.setDrawCrosshairsAllPanes(false);
						plugin.setCanvasLabelAllPanes("Display Canvas");
						return;
					case IMAGE_CLOSED:
						updateStatusText("Tracing image is no longer available...");
						disableImageDependentComponents();
						plugin.discardFill(false);
						quitMenuItem.setEnabled(true);
						return;
					default:
						SNT.error("BUG: switching to an unknown state");
						return;
				}

				plugin.updateAllViewers();
			}

		});

		currentState = newState;
	}

	/**
	 * Gets current UI state.
	 *
	 * @return the current state, e.g., {@link SNTUI#WAITING_TO_START_PATH},
	 *         {@link SNTUI#ANALYSIS_MODE}, etc.
	 */
	public int getState() {
		return currentState;
	}

	private boolean isStackAvailable() {
		return plugin != null && !plugin.is2D();
	}

	private JPanel sourcePanel() { // User inputs for multidimensional images

		final JPanel sourcePanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;

		final boolean hasChannels = plugin.getImagePlus().getNChannels() > 1;
		final boolean hasFrames = plugin.getImagePlus().getNFrames() > 1;
		final JPanel positionPanel = new JPanel(new FlowLayout(FlowLayout.LEADING,
			4, 0));
		positionPanel.add(GuiUtils.leftAlignedLabel("Channel", hasChannels));
		final JSpinner channelSpinner = GuiUtils.integerSpinner(plugin.channel, 1,
			plugin.getImagePlus().getNChannels(), 1);
		positionPanel.add(channelSpinner);
		positionPanel.add(GuiUtils.leftAlignedLabel(" Frame", hasFrames));
		final JSpinner frameSpinner = GuiUtils.integerSpinner(plugin.frame, 1,
			plugin.getImagePlus().getNFrames(), 1);
		positionPanel.add(frameSpinner);
		final JButton applyPositionButton = new JButton("Reload");
		final ChangeListener spinnerListener = new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				applyPositionButton.setText(((int) channelSpinner
					.getValue() == plugin.channel && (int) frameSpinner
						.getValue() == plugin.frame) ? "Reload" : "Apply");
			}
		};
		channelSpinner.addChangeListener(spinnerListener);
		frameSpinner.addChangeListener(spinnerListener);
		channelSpinner.setEnabled(hasChannels);
		frameSpinner.setEnabled(hasFrames);
		applyPositionButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (getState() == IMAGE_CLOSED || getState() == ANALYSIS_MODE) {
					guiUtils.error("Tracing image is not available.");
					return;
				}
				final int newC = (int) channelSpinner.getValue();
				final int newT = (int) frameSpinner.getValue();
				final boolean reload = newC == plugin.channel && newT == plugin.frame;
				if (!reload && !guiUtils.getConfirmation(
					"You are currently tracing position C=" + plugin.channel + ", T=" +
						plugin.frame + ". Start tracing C=" + newC + ", T=" + newT + "?",
					"Change Hyperstack Position?"))
				{
					return;
				}
				plugin.reloadImage(newC, newT);
				preprocess.setSelected(false);
				plugin.showMIPOverlays(0);
				showStatus(reload ? "Image reloaded into memory..." : null, true);
			}
		});
		positionPanel.add(applyPositionButton);
		sourcePanel.add(positionPanel, gdb);
		return sourcePanel;
	}

	private JPanel viewsPanel() {
		final JPanel viewsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;

		if (!plugin.nonInteractiveSession) {
			final JPanel mipPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0,
				0));
			final JCheckBox mipOverlayCheckBox = new JCheckBox("Overlay MIP(s) at");
			mipOverlayCheckBox.setEnabled(!plugin.is2D());
			mipPanel.add(mipOverlayCheckBox);
			final JSpinner mipSpinner = GuiUtils.integerSpinner(20, 10, 80, 1);
			mipSpinner.setEnabled(!plugin.is2D());
			mipSpinner.addChangeListener(new ChangeListener() {

				@Override
				public void stateChanged(final ChangeEvent e) {
					mipOverlayCheckBox.setSelected(false);
				}
			});
			mipPanel.add(mipSpinner);
			mipPanel.add(GuiUtils.leftAlignedLabel(" % opacity", !plugin.is2D()));
			mipOverlayCheckBox.addActionListener(new ActionListener() {

				@Override
				public void actionPerformed(final ActionEvent e) {
					plugin.showMIPOverlays((mipOverlayCheckBox.isSelected())
						? (int) mipSpinner.getValue() * 0.01 : 0);
				}
			});
			viewsPanel.add(mipPanel, gdb);
			++gdb.gridy;
		}
		final JCheckBox diametersCheckBox = new JCheckBox(
			"Draw diameters in XY view", plugin.getDrawDiametersXY());
		diametersCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				plugin.setDrawDiametersXY(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		viewsPanel.add(diametersCheckBox, gdb);
		++gdb.gridy;

		final JCheckBox zoomAllPanesCheckBox = new JCheckBox(
			"Apply zoom changes to all views", !plugin.isZoomAllPanesDisabled());
		zoomAllPanesCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				plugin.disableZoomAllPanes(e.getStateChange() == ItemEvent.DESELECTED);
			}
		});
		viewsPanel.add(zoomAllPanesCheckBox, gdb);
		++gdb.gridy;

		final String bLabel = (plugin.getSinglePane()) ? "Display" : "Rebuild";
		final JButton refreshPanesButton = new JButton(bLabel + " ZY/XZ Views");
		refreshPanesButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (getState() == IMAGE_CLOSED) {
					guiUtils.error("Tracing image is no longer available.");
					return;
				}
				plugin.rebuildZYXZpanes();
				showStatus("ZY/XZ views reloaded...", true);
				refreshPanesButton.setText("Rebuild ZY/XZ views");
				arrangeCanvases();
			}
		});
		gdb.fill = GridBagConstraints.NONE;
		viewsPanel.add(refreshPanesButton, gdb);
		return viewsPanel;
	}

	private JPanel tracingPanel() {
		final JPanel tPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		final JCheckBox confirmTemporarySegmentsCheckbox = new JCheckBox(
			"Confirm temporary segments", confirmTemporarySegments);
		final JCheckBox confirmCheckbox = new JCheckBox(
			"Pressing 'Y' twice finishes path", finishOnDoubleConfimation);
		final JCheckBox finishCheckbox = new JCheckBox(
			"Pressing 'N' twice cancels path", discardOnDoubleCancellation);
		confirmTemporarySegmentsCheckbox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				confirmTemporarySegments = (e.getStateChange() == ItemEvent.SELECTED);
				confirmCheckbox.setEnabled(confirmTemporarySegments);
				finishCheckbox.setEnabled(confirmTemporarySegments);
			}
		});

		confirmCheckbox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				finishOnDoubleConfimation = (e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		confirmCheckbox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				discardOnDoubleCancellation = (e
					.getStateChange() == ItemEvent.SELECTED);
			}
		});
		tPanel.add(confirmTemporarySegmentsCheckbox, gdb);
		++gdb.gridy;
		gdb.insets = new Insets(0, MARGIN * 3, 0, 0);
		tPanel.add(confirmCheckbox, gdb);
		++gdb.gridy;
		tPanel.add(finishCheckbox, gdb);
		++gdb.gridy;
		gdb.insets = new Insets(0, 0, 0, 0);
		return tPanel;

	}

	private JPanel interactionPanel() {
		final JPanel intPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		intPanel.add(extraColorsPanel(), gdb);
		++gdb.gridy;
		intPanel.add(nodePanel(), gdb);
		++gdb.gridy;

		final JCheckBox canvasCheckBox = new JCheckBox(
			"Activate canvas on mouse hovering", plugin.autoCanvasActivation);
		canvasCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				plugin.enableAutoActivation(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		intPanel.add(canvasCheckBox, gdb);
		++gdb.gridy;
		return intPanel;
	}

	private JPanel nodePanel() {
		final JSpinner nodeSpinner = GuiUtils.doubleSpinner(plugin.getXYCanvas()
			.nodeDiameter(), 0, 100, 1, 0);
		nodeSpinner.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				final double value = (double) (nodeSpinner.getValue());
				plugin.xy_tracer_canvas.setNodeDiameter(value);
				if (!plugin.getSinglePane()) {
					plugin.xz_tracer_canvas.setNodeDiameter(value);
					plugin.zy_tracer_canvas.setNodeDiameter(value);
				}
				plugin.updateAllViewers();
			};
		});
		final JButton defaultsButton = new JButton("Default");
		defaultsButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				plugin.xy_tracer_canvas.setNodeDiameter(-1);
				if (!plugin.getSinglePane()) {
					plugin.xz_tracer_canvas.setNodeDiameter(-1);
					plugin.zy_tracer_canvas.setNodeDiameter(-1);
				}
				nodeSpinner.setValue(plugin.xy_tracer_canvas.nodeDiameter());
				showStatus("Node scale reset", true);
			}
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Path nodes rendering scale: ", true));
		c.gridx = 1;
		p.add(nodeSpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defaultsButton);
		return p;
	}

	private JPanel extraColorsPanel() {

		final LinkedHashMap<String, Color> hm = new LinkedHashMap<>();
		hm.put("Canvas annotations", plugin.getXYCanvas().getAnnotationsColor());
		hm.put("Fills", plugin.getXYCanvas().getFillColor());
		hm.put("Unconfirmed paths", plugin.getXYCanvas().getUnconfirmedPathColor());
		hm.put("Temporary paths", plugin.getXYCanvas().getTemporaryPathColor());

		final JComboBox<String> colorChoice = new JComboBox<>();
		for (final Entry<String, Color> entry : hm.entrySet())
			colorChoice.addItem(entry.getKey());

		final String selectedKey = String.valueOf(colorChoice.getSelectedItem());
		final ColorChooserButton cChooser = new ColorChooserButton(hm.get(
			selectedKey), "Change...", 1, SwingConstants.RIGHT);

		colorChoice.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				cChooser.setSelectedColor(hm.get(String.valueOf(colorChoice
					.getSelectedItem())), false);
			}
		});

		cChooser.addColorChangedListener(new ColorChangedListener() {

			@Override
			public void colorChanged(final Color newColor) {
				final String selectedKey = String.valueOf(colorChoice
					.getSelectedItem());
				switch (selectedKey) {
					case "Canvas annotations":
						plugin.setAnnotationsColorAllPanes(newColor);
						break;
					case "Fills":
						plugin.getXYCanvas().setFillColor(newColor);
						if (!plugin.getSinglePane()) {
							plugin.getZYCanvas().setFillColor(newColor);
							plugin.getXZCanvas().setFillColor(newColor);
						}
						break;
					case "Unconfirmed paths":
						plugin.getXYCanvas().setUnconfirmedPathColor(newColor);
						if (!plugin.getSinglePane()) {
							plugin.getZYCanvas().setUnconfirmedPathColor(newColor);
							plugin.getXZCanvas().setUnconfirmedPathColor(newColor);
						}
						break;
					case "Temporary paths":
						plugin.getXYCanvas().setTemporaryPathColor(newColor);
						if (!plugin.getSinglePane()) {
							plugin.getZYCanvas().setTemporaryPathColor(newColor);
							plugin.getXZCanvas().setTemporaryPathColor(newColor);
						}
						break;
					default:
						throw new IllegalArgumentException("Unrecognized option");
				}
				plugin.updateAllViewers();
			}
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Colors: ", true));
		c.gridx = 1;
		p.add(colorChoice, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(cChooser);
		return p;
	}

	private JPanel miscPanel() {
		final JPanel miscPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		final JCheckBox winLocCheckBox = new JCheckBox("Remember window locations",
			plugin.prefs.isSaveWinLocations());
		winLocCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				plugin.prefs.setSaveWinLocations(e
					.getStateChange() == ItemEvent.SELECTED);
			}
		});
		miscPanel.add(winLocCheckBox, gdb);
		++gdb.gridy;
		final JCheckBox compressedXMLCheckBox = new JCheckBox(
			"Use compression when saving traces", plugin.useCompressedXML);
		compressedXMLCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				plugin.useCompressedXML = (e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		miscPanel.add(compressedXMLCheckBox, gdb);
		++gdb.gridy;
		final JCheckBox debugCheckBox = new JCheckBox("Debug mode", SNT
			.isDebugMode());
		debugCheckBox.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				SNT.setDebugMode(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		miscPanel.add(debugCheckBox, gdb);
		++gdb.gridy;
		final JButton resetbutton = GuiUtils.smallButton("Reset Preferences...");
		resetbutton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (guiUtils.getConfirmation(
					"Reset preferences to defaults? (Restart required)", "Reset?"))
				{
					plugin.prefs.resetOptions();
					guiUtils.centeredMsg(
						"You should now restart SNT for changes to take effect",
						"Restart required");
				}
			}
		});
		gdb.fill = GridBagConstraints.NONE;
		miscPanel.add(resetbutton, gdb);
		return miscPanel;
	}

	@SuppressWarnings("deprecation")
	private JPanel legacy3DViewerPanel() {

		final String VIEWER_NONE = "None";
		final String VIEWER_WITH_IMAGE = "New with image...";
		final String VIEWER_EMPTY = "New without image";

		// Define UI components
		final JComboBox<String> univChoice = new JComboBox<>();
		final JButton applyUnivChoice = new JButton("Apply");
		final JComboBox<String> displayChoice = new JComboBox<>();
		final JButton applyDisplayChoice = new JButton("Apply");
		final JButton refreshList = GuiUtils.smallButton("Refresh List");
		final JButton applyLabelsImage = new JButton(
			"Apply Color Labels...");
		final JButton getCorrespondences = new JButton(
				"Compare Tracings...");

		final LinkedHashMap<String, Image3DUniverse> hm = new LinkedHashMap<>();
		hm.put(VIEWER_NONE, null);
		if (!plugin.nonInteractiveSession && !plugin.is2D()) {
			hm.put(VIEWER_WITH_IMAGE, null);
		}
		hm.put(VIEWER_EMPTY, null);
		for (final Image3DUniverse univ : Image3DUniverse.universes) {
			hm.put(univ.allContentsString(), univ);
		}

		// Build choices widget for viewers
		univChoice.setPrototypeDisplayValue(VIEWER_WITH_IMAGE);
		for (final Entry<String, Image3DUniverse> entry : hm.entrySet()) {
			univChoice.addItem(entry.getKey());
		}
		univChoice.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {

				final boolean none = VIEWER_NONE.equals(String.valueOf(univChoice
					.getSelectedItem()));
				applyUnivChoice.setEnabled(!none);
			}
		});
		applyUnivChoice.addActionListener(new ActionListener() {

			private void resetChoice() {
				univChoice.setSelectedItem(VIEWER_NONE);
				applyUnivChoice.setEnabled(false);
				final boolean validViewer = plugin.use3DViewer && plugin
					.get3DUniverse() != null;
				displayChoice.setEnabled(validViewer);
				applyDisplayChoice.setEnabled(validViewer);
				applyLabelsImage.setEnabled(validViewer);
				getCorrespondences.setEnabled(validViewer);
			}

			@Override
			public void actionPerformed(final ActionEvent e) {

				applyUnivChoice.setEnabled(false);

				final String selectedKey = String.valueOf(univChoice.getSelectedItem());
				if (VIEWER_NONE.equals(selectedKey)) {
					plugin.set3DUniverse(null);
					resetChoice();
					return;
				}

				Image3DUniverse univ;
				univ = hm.get(selectedKey);
				if (univ == null) {

					// Presumably a new viewer was chosen. Let's double-check
					final boolean newViewer = selectedKey.equals(VIEWER_WITH_IMAGE) ||
						selectedKey.equals(VIEWER_EMPTY);
					if (!newViewer && !guiUtils.getConfirmation(
						"The chosen viewer does not seem to be available. Create a new one?",
						"Viewer Unavailable"))
					{
						resetChoice();
						return;
					}
					univ = new Image3DUniverse(512, 512);
				}

				plugin.set3DUniverse(univ);

				if (VIEWER_WITH_IMAGE.equals(selectedKey)) {

					final int defResFactor = Content.getDefaultResamplingFactor(plugin
						.getImagePlus(), ContentConstants.VOLUME);
					final Double userResFactor = guiUtils.getDouble(
						"<HTML><body><div style='width:" + Math.min(getWidth(), 500) +
							";'>" +
							"Please specify the image resampling factor. The default factor for current image is " +
							defResFactor + ".", "Image Resampling Factor", defResFactor);

					if (userResFactor == null) { // user pressed cancel
						plugin.set3DUniverse(null);
						resetChoice();
						return;
					}

					final int resFactor = (Double.isNaN(userResFactor) ||
						userResFactor < 1) ? defResFactor : userResFactor.intValue();
					plugin.prefs.set3DViewerResamplingFactor(resFactor);
					plugin.updateImageContent(resFactor);
				}

				// Add PointListener/Keylistener
				new QueueJumpingKeyListener(plugin, univ);
				ImageWindow3D window = univ.getWindow();
				if (univ.getWindow() == null) {
					window = new ImageWindow3D("SNT Legacy 3D Viewer", univ);
					window.setSize(512, 512);
					univ.init(window);
				}
				else {
					univ.resetView();
				}
				window.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosed(final WindowEvent e) {
						resetChoice();
					}
				});
				window.setVisible(true);
				resetChoice();
				showStatus("3D Viewer enabled: " + selectedKey, true);
			}
		});

		// Build widget for rendering choices
		displayChoice.addItem("Surface reconstructions");
		displayChoice.addItem("Lines");
		displayChoice.addItem("Lines and discs");
		applyDisplayChoice.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {

				switch (String.valueOf(displayChoice.getSelectedItem())) {
					case "Lines":
						plugin.setPaths3DDisplay(SimpleNeuriteTracer.DISPLAY_PATHS_LINES);
						break;
					case "Lines and discs":
						plugin.setPaths3DDisplay(
							SimpleNeuriteTracer.DISPLAY_PATHS_LINES_AND_DISCS);
						break;
					default:
						plugin.setPaths3DDisplay(SimpleNeuriteTracer.DISPLAY_PATHS_SURFACE);
						break;
				}
			}
		});

		// Build refresh button
		refreshList.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				for (final Image3DUniverse univ : Image3DUniverse.universes) {
					if (hm.containsKey(univ.allContentsString())) continue;
					hm.put(univ.allContentsString(), univ);
					univChoice.addItem(univ.allContentsString());
				}
				showStatus("Viewers list updated...", true);
			}
		});

		// Build load labels button
		applyLabelsImage.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final File imageFile = guiUtils.openFile("Choose labels image",
					plugin.prefs.getRecentFile(), null);
				if (imageFile == null) return; // user pressed cancel
				try {
					plugin.statusService.showStatus(("Loading " + imageFile.getName()));
					final Dataset ds = plugin.datasetIOService.open(imageFile
						.getAbsolutePath());
					final ImagePlus colorImp = plugin.convertService.convert(ds,
						ImagePlus.class);
					showStatus("Applying color labels...", false);
					plugin.setColorImage(colorImp);
					showStatus("Labels image loaded...", true);

				}
				catch (final IOException exc) {
					guiUtils.error("Could not open " + imageFile.getAbsolutePath() +
						". Maybe it is not a valid image?", "IO Error");
					exc.printStackTrace();
					return;
				}
			}
		});

		getCorrespondences.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final File tracesFile = guiUtils.openFile("Select tracings...", null,
					Collections.singletonList(".swc"));
				if (tracesFile == null) return;
				if (!tracesFile.exists()) {
					guiUtils.error(tracesFile.getAbsolutePath() + " is not available");
					return;
				}
				plugin.showCorrespondencesTo(tracesFile, guiUtils.getColor(
					"Rendering Color", Color.RED), 10);
			}
		}
		);

		// Set defaults
		univChoice.setSelectedItem(VIEWER_NONE);
		applyUnivChoice.setEnabled(false);
		displayChoice.setEnabled(false);
		applyDisplayChoice.setEnabled(false);
		applyLabelsImage.setEnabled(false);
		getCorrespondences.setEnabled(false);

		// Build panel
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;

		// row 1
		c.gridy = 0;
		c.gridx = 0;
		p.add(GuiUtils.leftAlignedLabel("Viewer: ", true), c);
		c.gridx++;
		c.weightx = 1;
		p.add(univChoice, c);
		c.gridx++;
		c.weightx = 0;
		p.add(applyUnivChoice, c);
		c.gridx++;

		// row 2
		c.gridy++;
		c.gridx = 1;
		c.gridwidth = 1;
		c.anchor = GridBagConstraints.EAST;
		c.fill = GridBagConstraints.NONE;
		p.add(refreshList, c);

		// row 3
		c.gridy++;
		c.gridx = 0;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Mode: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(displayChoice, c);
		c.gridx++;
		c.gridwidth = GridBagConstraints.NONE;
		p.add(applyDisplayChoice, c);
		c.gridx++;

		// row 3
		c.ipady = MARGIN;
		c.gridy++;
		c.gridx = 0;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Actions: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING,
			MARGIN / 2, MARGIN / 2));
		buttonPanel.add(applyLabelsImage);
		buttonPanel.add(getCorrespondences);
		p.add(buttonPanel, c);
		return p;
	}

	private JPanel statusButtonPanel() {
		final JPanel statusChoicesPanel = new JPanel();
		statusChoicesPanel.setLayout(new GridBagLayout());
		statusChoicesPanel.setBorder(new EmptyBorder(0, 0, MARGIN * 2, 0));
		final GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.CENTER;
		gbc.fill = GridBagConstraints.NONE;
		gbc.ipadx = 0;
		gbc.ipady = 0;

		gbc.insets = new Insets(0, 0, 0, 0);
		keepSegment = GuiUtils.smallButton(hotKeyLabel("Yes", "Y"));
		keepSegment.addActionListener(listener);
		gbc.weightx = 0.25;
		statusChoicesPanel.add(keepSegment, gbc);
		gbc.ipadx = 2;
		junkSegment = GuiUtils.smallButton(hotKeyLabel("&thinsp;No&thinsp;", "N"));
		junkSegment.addActionListener(listener);
		gbc.gridx = 1;
		statusChoicesPanel.add(junkSegment, gbc);
		completePath = GuiUtils.smallButton(hotKeyLabel("Finish", "F"));
		completePath.addActionListener(listener);
		gbc.gridx = 2;
		statusChoicesPanel.add(completePath, gbc);
		gbc.gridx = 3;
		abortButton = GuiUtils.smallButton(hotKeyLabel(hotKeyLabel("Cancel/Esc",
			"C"), "Esc"));
		abortButton.addActionListener(listener);
		gbc.gridx = 4;
		gbc.ipadx = 0;
		statusChoicesPanel.add(abortButton, gbc);
		return statusChoicesPanel;
	}

	private JPanel statusPanel() {
		final JPanel statusPanel = new JPanel();
		statusPanel.setLayout(new BorderLayout());
		statusText = new JLabel("Loading SNT...");
		statusText.setOpaque(true);
		statusText.setBackground(Color.WHITE);
		statusText.setBorder(BorderFactory.createCompoundBorder(BorderFactory
			.createBevelBorder(BevelBorder.LOWERED), BorderFactory.createEmptyBorder(
				MARGIN, MARGIN, MARGIN, MARGIN)));
		statusPanel.add(statusText, BorderLayout.CENTER);
		final JPanel buttonPanel = statusButtonPanel();
		statusPanel.add(buttonPanel, BorderLayout.SOUTH);
		statusPanel.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN,
			MARGIN * 2, MARGIN));
		return statusPanel;
	}

	private JPanel filteredImagePanel() {
		filteredImgPathField = new JTextField();
		filteredImgLoadButton = GuiUtils.smallButton("Choose...");
		filteredImgParserChoice = new JComboBox<>();
		filteredImgParserChoice.addItem("Simple Neurite Tracer");
		filteredImgParserChoice.addItem("ITK: Tubular Geodesics");
		filteredImgInitButton = GuiUtils.smallButton("Initialize...");
		filteredImgActivateCheckbox = new JCheckBox(hotKeyLabel(
			"Trace using filtered Image", "I"));
		filteredImgActivateCheckbox.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				enableFilteredImgTracing(filteredImgActivateCheckbox.isSelected());
			}
		});

		filteredImgPathField.getDocument().addDocumentListener(
			new DocumentListener()
			{

				@Override
				public void changedUpdate(final DocumentEvent e) {
					updateFilteredFileField();
				}

				@Override
				public void removeUpdate(final DocumentEvent e) {
					updateFilteredFileField();
				}

				@Override
				public void insertUpdate(final DocumentEvent e) {
					updateFilteredFileField();
				}

			});

		filteredImgLoadButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final File file = guiUtils.openFile("Choose filtered image", new File(
					filteredImgPathField.getText()), filteredImgAllowedExts);
				if (file == null) return;
				filteredImgPathField.setText(file.getAbsolutePath());
			}
		});

		filteredImgInitButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (plugin.isTracingOnFilteredImageAvailable()) { // Toggle: set action
																													// to disable filtered
																													// tracing
					if (!guiUtils.getConfirmation("Disable access to filtered image?",
						"Unload Image?")) return;

					// reset cached filtered image/Tubular Geodesics
					plugin.filteredData = null;
					plugin.doSearchOnFilteredData = false;
					if (plugin.tubularGeodesicsTracingEnabled) {
						if (plugin.tubularGeodesicsThread != null)
							plugin.tubularGeodesicsThread.requestStop();
						plugin.tubularGeodesicsThread = null;
						plugin.tubularGeodesicsTracingEnabled = false;
					}
					System.gc();
					updateFilteredImgFields();

				}
				else { // toggle: set action to enable filtered tracing
					final File file = new File(filteredImgPathField.getText());
					if (!SNT.fileAvailable(file)) {
						guiUtils.error(file.getAbsolutePath() +
							" is not available. Image could not be loaded.",
							"File Unavailable");
						return;
					}
					plugin.setFilteredImage(file);

					if (filteredImgParserChoice.getSelectedIndex() == 0) { // SNT if
																																	// (!"Simple
																																	// Neurite
						// Tracer".equals(parserChoice.getSelectedItem())
						// {
						final int byteDepth = 32 / 8;
						final ImagePlus tracingImp = plugin.getImagePlus();
						final long megaBytesExtra = (((long) tracingImp.getWidth()) *
							tracingImp.getHeight() * tracingImp.getNSlices() * byteDepth *
							2) / (1024 * 1024);
						final long maxMemory = Runtime.getRuntime().maxMemory() / (1024 *
							1024);
						if (!guiUtils.getConfirmation("Load " + file.getAbsolutePath() +
							"? This operation will likely require " + megaBytesExtra +
							"MiB of RAM (currently available: " + maxMemory + " MiB).",
							"Confirm Loading?")) return;
						loadFilteredImage();

					}
					else if (filteredImgParserChoice.getSelectedIndex() == 1) { // Tubular
																																			// Geodesics

						if (ClassUtils.loadClass(
							"FijiITKInterface.TubularGeodesics") == null)
						{
							guiUtils.error(
								"The 'Tubular Geodesics' plugin does not seem to be installed!");
							return;
						}
						plugin.tubularGeodesicsTracingEnabled = true;
						updateFilteredImgFields();
					}
				}
			}
		});

		filterChoice = new JComboBox<>();
		filterChoice.addItem("None");
		filterChoice.addItem("Frangi Vesselness");
		filterChoice.addItem("Tubeness");
		filterChoice.addItem("Tubular Geodesics");
		filterChoice.addItem("Other...");
		filterChoice.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				displayFiltered.setEnabled(filterChoice.getSelectedIndex() > 0);
				guiUtils.centeredMsg("This feature is not yet implemented",
					"Not Yet Implemented");
				filterChoice.setSelectedIndex(0);
			}
		});

		filteredImgPanel = new JPanel();
		filteredImgPanel.setLayout(new GridBagLayout());
		GridBagConstraints c = GuiUtils.defaultGbc();
		c.gridwidth = GridBagConstraints.REMAINDER;

		// Header
		GuiUtils.addSeparator(filteredImgPanel, "Tracing on Filtered Image:", true,
			c);

		c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;

		// row 1
		c.gridy = 1;
		c.gridx = 0;
		filteredImgPanel.add(GuiUtils.leftAlignedLabel("Image: ", true), c);
		c.gridx++;
		c.weightx = 1;
		filteredImgPanel.add(filteredImgPathField, c);
		c.gridx++;
		c.weightx = 0;
		filteredImgPanel.add(filteredImgLoadButton, c);
		c.gridx++;

		// row 2
		c.gridy++;
		c.gridx = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		filteredImgPanel.add(GuiUtils.leftAlignedLabel("Parser: ", true), c);
		c.gridx++;
		filteredImgPanel.add(filteredImgParserChoice, c);
		c.gridx++;
		c.gridwidth = GridBagConstraints.REMAINDER;
		filteredImgPanel.add(filteredImgInitButton, c);
		c.gridx++;

		// row 3
		c.gridy++;
		c.gridx = 0;
		filteredImgPanel.add(filteredImgActivateCheckbox, c);
		return filteredImgPanel;
	}

	private void loadFilteredImage() {
		filteredImgLoadingWorker = new SwingWorker<Object, Object>() {

			@Override
			protected Object doInBackground() throws Exception {

				try {
					plugin.loadFilteredImage();
				}
				catch (final IllegalArgumentException e1) {
					guiUtils.error("Could not load " + plugin.getFilteredImage()
						.getAbsolutePath() + ":<br>" + e1.getMessage());
					return null;
				}
				catch (final IOException e2) {
					guiUtils.error("Loading of image failed. See Console for details");
					e2.printStackTrace();
					return null;
				}
				catch (final OutOfMemoryError e3) {
					plugin.filteredData = null;
					guiUtils.error(
						"It seems you there is not enough memory to proceed. See Console for details");
					e3.printStackTrace();
				}
				return null;
			}

			@Override
			protected void done() {
				changeState(WAITING_TO_START_PATH);
				updateFilteredImgFields();
			}
		};
		changeState(LOADING_FILTERED_IMAGE);
		filteredImgLoadingWorker.run();

	}

	private void updateFilteredImgFields() {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				final boolean successfullyLoaded = plugin
					.isTracingOnFilteredImageAvailable();
				filteredImgParserChoice.setEnabled(!successfullyLoaded);
				filteredImgPathField.setEnabled(!successfullyLoaded);
				filteredImgLoadButton.setEnabled(!successfullyLoaded);
				filteredImgInitButton.setText((successfullyLoaded) ? "Reset"
					: "Initialize...");
				filteredImgInitButton.setEnabled(successfullyLoaded);
				filteredImgActivateCheckbox.setEnabled(successfullyLoaded);
				if (!successfullyLoaded) filteredImgActivateCheckbox.setSelected(false);
			}
		});
	}

	private void updateFilteredFileField() {
		if (filteredImgPathField == null) return;
		final String path = filteredImgPathField.getText();
		final boolean validFile = path != null && SNT.fileAvailable(new File(
			path)) && filteredImgAllowedExts.stream().anyMatch(e -> path.endsWith(e));
		filteredImgPathField.setForeground((validFile) ? new JTextField()
			.getForeground() : Color.RED);
		filteredImgInitButton.setEnabled(validFile);
		filteredImgParserChoice.setEnabled(validFile);
		filteredImgActivateCheckbox.setEnabled(validFile);
		filteredImgPathField.setToolTipText((validFile) ? path
			: "Not a valid file path");
	}

	private JMenuBar createMenuBar() {
		final JMenuBar menuBar = new JMenuBar();
		final JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);
		final JMenu importSubmenu = new JMenu("Import");
		final JMenu exportSubmenu = new JMenu("Export (All Paths)");
		final JMenu analysisMenu = new JMenu("Utilities");
		menuBar.add(analysisMenu);
		final JMenu viewMenu = new JMenu("View");
		menuBar.add(viewMenu);
		final ScriptInstaller installer = new ScriptInstaller(plugin.getContext(), this);
		menuBar.add(installer.getScriptsMenu());
		menuBar.add(helpMenu());

		loadTracesMenuItem = new JMenuItem("Load Traces...");
		loadTracesMenuItem.addActionListener(listener);
		fileMenu.add(loadTracesMenuItem);

		saveMenuItem = new JMenuItem("Save Traces...");
		saveMenuItem.addActionListener(listener);
		fileMenu.add(saveMenuItem);
		final JMenuItem saveTable = new JMenuItem("Save Measurements...");
		saveTable.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				pmUI.saveTable();
				return;
			}
		});
		fileMenu.add(saveTable);

		sendToTrakEM2 = new JMenuItem("Send to TrakEM2");
		sendToTrakEM2.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				plugin.notifyListeners(new SNTEvent(SNTEvent.SEND_TO_TRAKEM2));
			}
		});
		fileMenu.addSeparator();
		fileMenu.add(sendToTrakEM2);
		fileMenu.addSeparator();

		loadSWCMenuItem = new JMenuItem("(e)SWC...");
		loadSWCMenuItem.addActionListener(listener);
		importSubmenu.add(loadSWCMenuItem);
		loadLabelsMenuItem = new JMenuItem("Labels (AmiraMesh)...");
		loadLabelsMenuItem.addActionListener(listener);
		importSubmenu.add(loadLabelsMenuItem);
		fileMenu.add(importSubmenu);

		exportAllSWCMenuItem = new JMenuItem("SWC...");
		exportAllSWCMenuItem.addActionListener(listener);
		exportSubmenu.add(exportAllSWCMenuItem);
		exportCSVMenuItem = new JMenuItem("CSV Properties...");
		exportCSVMenuItem.addActionListener(listener);
		exportSubmenu.add(exportCSVMenuItem);
		fileMenu.add(exportSubmenu);

		fileMenu.addSeparator();
		quitMenuItem = new JMenuItem("Quit");
		quitMenuItem.addActionListener(listener);
		fileMenu.add(quitMenuItem);

		analysisMenu.addSeparator();
		measureMenuItem = new JMenuItem("Quick Statistics");
		measureMenuItem.addActionListener(listener);
		strahlerMenuItem = new JMenuItem("Strahler Analysis");
		strahlerMenuItem.addActionListener(listener);
		plotMenuItem = new JMenuItem("Plot Traces...");
		plotMenuItem.addActionListener(listener);

		analysisMenu.add(plotMenuItem);
		analysisMenu.add(measureMenuItem);
		analysisMenu.add(shollAnalysisHelpMenuItem());
		analysisMenu.add(strahlerMenuItem);
		analysisMenu.addSeparator();

		final JCheckBoxMenuItem xyCanvasMenuItem = new JCheckBoxMenuItem(
			"Hide XY View");
		xyCanvasMenuItem.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				toggleWindowVisibility(MultiDThreePanes.XY_PLANE, xyCanvasMenuItem);
			}
		});
		viewMenu.add(xyCanvasMenuItem);
		final JCheckBoxMenuItem zyCanvasMenuItem = new JCheckBoxMenuItem(
			"Hide ZY View");
		zyCanvasMenuItem.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				toggleWindowVisibility(MultiDThreePanes.ZY_PLANE, zyCanvasMenuItem);
			}
		});
		viewMenu.add(zyCanvasMenuItem);
		final JCheckBoxMenuItem xzCanvasMenuItem = new JCheckBoxMenuItem(
			"Hide XZ View");
		xzCanvasMenuItem.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				toggleWindowVisibility(MultiDThreePanes.XZ_PLANE, xzCanvasMenuItem);
			}
		});
		viewMenu.add(xzCanvasMenuItem);
		final JCheckBoxMenuItem threeDViewerMenuItem = new JCheckBoxMenuItem(
			"Hide 3D View");
		threeDViewerMenuItem.setEnabled(plugin.use3DViewer);
		threeDViewerMenuItem.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				if (plugin.get3DUniverse() != null) plugin.get3DUniverse().getWindow()
					.setVisible(e.getStateChange() == ItemEvent.DESELECTED);
			}
		});
		viewMenu.add(threeDViewerMenuItem);
		// viewMenu.addSeparator();
		// final JMenuItem resetZoomMenuItem = new JMenuItem("Reset Zoom Levels");
		// resetZoomMenuItem.addActionListener(new ActionListener() {
		//
		// @Override
		// public void actionPerformed(final ActionEvent e) {
		// plugin.zoom100PercentAllPanes();
		// }
		// });
		// viewMenu.add(resetZoomMenuItem);
		viewMenu.addSeparator();
		final JMenuItem arrangeWindowsMenuItem = new JMenuItem("Arrange Views");
		arrangeWindowsMenuItem.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				arrangeCanvases();
			}
		});
		viewMenu.add(arrangeWindowsMenuItem);
		return menuBar;
	}

	private JPanel renderingPanel() {

		final JPanel col1 = new JPanel();
		col1.setLayout(new BoxLayout(col1, BoxLayout.Y_AXIS));
		showPathsAll = new JRadioButton(hotKeyLabel("All", "A"),
			!plugin.showOnlySelectedPaths);
		showPathsAll.addItemListener(listener);
		showPathsSelected = new JRadioButton("Selected",
			plugin.showOnlySelectedPaths);
		showPathsSelected.addItemListener(listener);

		final ButtonGroup col1Group = new ButtonGroup();
		col1Group.add(showPathsAll);
		col1Group.add(showPathsSelected);
		col1.add(showPathsAll);
		col1.add(showPathsSelected);

		final JPanel col2 = new JPanel();
		col2.setLayout(new BoxLayout(col2, BoxLayout.Y_AXIS));
		final ButtonGroup col2Group = new ButtonGroup();
		showPartsAll = new JRadioButton(hotKeyLabel("Z-stack projection", "Z"));
		col2Group.add(showPartsAll);
		final JPanel row1Panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row1Panel.add(showPartsAll);
		showPartsAll.setSelected(true);
		showPartsAll.setEnabled(isStackAvailable());
		showPartsAll.addItemListener(listener);

		showPartsNearby = new JRadioButton("Up to");
		col2Group.add(showPartsNearby);
		showPartsNearby.setEnabled(isStackAvailable());
		showPartsNearby.addItemListener(listener);
		final JPanel nearbyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0,
			0));
		nearbyPanel.add(showPartsNearby);
		nearbyFieldSpinner = GuiUtils.integerSpinner(plugin.depth == 1 ? 1 : 2, 1,
			plugin.depth, 1);
		nearbyFieldSpinner.setEnabled(isStackAvailable());
		nearbyFieldSpinner.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				showPartsNearby.setSelected(true);
				plugin.justDisplayNearSlices(true, (int) nearbyFieldSpinner.getValue());
			}
		});

		nearbyPanel.add(nearbyFieldSpinner);
		nearbyPanel.add(GuiUtils.leftAlignedLabel(" nearby slices",
			isStackAvailable()));
		col2.add(row1Panel);
		col2.add(nearbyPanel);

		final JPanel viewOptionsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.weightx = 0.5;
		viewOptionsPanel.add(col1, gdb);
		gdb.gridx = 1;
		viewOptionsPanel.add(col2, gdb);

		return viewOptionsPanel;
	}

	private JPanel colorOptionsPanel() {
		final JPanel colorOptionsPanel = new JPanel();
		colorOptionsPanel.setLayout(new GridBagLayout());
		final GridBagConstraints cop_f = GuiUtils.defaultGbc();

		final JPanel colorButtonPanel = new JPanel();
		final ColorChooserButton colorChooser1 = new ColorChooserButton(
			plugin.selectedColor, "Selected Paths");
		colorChooser1.setName("Color for Selected Paths");
		colorChooser1.addColorChangedListener(new ColorChangedListener() {

			@Override
			public void colorChanged(final Color newColor) {
				plugin.setSelectedColor(newColor);
			}
		});
		final ColorChooserButton colorChooser2 = new ColorChooserButton(
			plugin.deselectedColor, "Deselected Paths");
		colorChooser2.setName("Color for Deselected Paths");
		colorChooser2.addColorChangedListener(new ColorChangedListener() {

			@Override
			public void colorChanged(final Color newColor) {
				plugin.setDeselectedColor(newColor);
			}
		});
		colorButtonPanel.add(colorChooser1);
		colorButtonPanel.add(colorChooser2);

		final JComboBox<String> pathsColorChoice = new JComboBox<>();
		pathsColorChoice.addItem("Default colors");
		pathsColorChoice.addItem("Path Manager tags (if any)");
		pathsColorChoice.setSelectedIndex(plugin.displayCustomPathColors ? 1 : 0);
		pathsColorChoice.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				plugin.displayCustomPathColors = !"Default colors".equals(
					pathsColorChoice.getSelectedItem());
				colorChooser1.setEnabled(!plugin.displayCustomPathColors);
				colorChooser2.setEnabled(!plugin.displayCustomPathColors);
				plugin.updateAllViewers();
			}
		});
		++cop_f.gridy;
		colorOptionsPanel.add(pathsColorChoice, cop_f);

		++cop_f.gridy;
		colorOptionsPanel.add(colorButtonPanel, cop_f);
		return colorOptionsPanel;
	}

	private JPanel snappingPanel() {

		final JPanel tracingOptionsPanel = new JPanel(new FlowLayout(
			FlowLayout.LEADING, 0, 0));
		useSnapWindow = new JCheckBox(hotKeyLabel("Enable Snapping within: XY",
			"S"), plugin.snapCursor);
		useSnapWindow.addItemListener(listener);
		tracingOptionsPanel.add(useSnapWindow);

		snapWindowXYsizeSpinner = GuiUtils.integerSpinner(
			plugin.cursorSnapWindowXY * 2,
			SimpleNeuriteTracer.MIN_SNAP_CURSOR_WINDOW_XY,
			SimpleNeuriteTracer.MAX_SNAP_CURSOR_WINDOW_XY * 2, 2);
		snapWindowXYsizeSpinner.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				plugin.cursorSnapWindowXY = (int) snapWindowXYsizeSpinner.getValue() /
					2;
			}
		});
		tracingOptionsPanel.add(snapWindowXYsizeSpinner);

		final JLabel z_spinner_label = GuiUtils.leftAlignedLabel("  Z ",
			isStackAvailable());
		z_spinner_label.setBorder(new EmptyBorder(0, 2, 0, 0));
		tracingOptionsPanel.add(z_spinner_label);
		snapWindowZsizeSpinner = GuiUtils.integerSpinner(plugin.cursorSnapWindowZ *
			2, SimpleNeuriteTracer.MIN_SNAP_CURSOR_WINDOW_Z,
			SimpleNeuriteTracer.MAX_SNAP_CURSOR_WINDOW_Z * 2, 2);
		snapWindowZsizeSpinner.setEnabled(isStackAvailable());
		snapWindowZsizeSpinner.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(final ChangeEvent e) {
				plugin.cursorSnapWindowZ = (int) snapWindowZsizeSpinner.getValue() / 2;
			}
		});
		tracingOptionsPanel.add(snapWindowZsizeSpinner);
		// ensure same alignment of all other panels using defaultGbc
		final JPanel container = new JPanel(new GridBagLayout());
		container.add(tracingOptionsPanel, GuiUtils.defaultGbc());
		return container;
	}

	private JPanel autoTracingPanel() {
		final JPanel autoTracePanel = new JPanel(new GridBagLayout());
		final GridBagConstraints atp_c = GuiUtils.defaultGbc();
		final JCheckBox aStarCheckBox = new JCheckBox("Enable A* search algorithm",
			plugin.isAstarEnabled());
		aStarCheckBox.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final boolean enable = aStarCheckBox.isSelected();
				if (!enable && !guiUtils.getConfirmation(
					"Disable computation of paths? All segmentation tasks will be disabled.",
					"Enable Manual Tracing?"))
				{
					aStarCheckBox.setSelected(true);
					return;
				}
				plugin.enableAstar(enable);
				setEnableAutoTracingComponents(enable);
			}
		});
		autoTracePanel.add(aStarCheckBox, atp_c);
		++atp_c.gridy;

		hessianPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints hc = GuiUtils.defaultGbc();
		preprocess = new JCheckBox();
		setSigma(plugin.getMinimumSeparation(), false);
		setMultiplier(4);
		updateHessianLabel();
		preprocess.addActionListener(listener);
		hessianPanel.add(preprocess, hc);
		++hc.gridy;

		// Add sigma ui
		final JPanel sigmaPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2,
			0));
		sigmaPanel.add(GuiUtils.leftAlignedLabel("Choose Sigma: ", plugin
			.isAstarEnabled()));
		final JButton editSigma = GuiUtils.smallButton(
			GuiListener.EDIT_SIGMA_MANUALLY);
		editSigma.addActionListener(listener);
		sigmaPanel.add(editSigma);
		final JButton sigmaWizard = GuiUtils.smallButton(
			GuiListener.EDIT_SIGMA_VISUALLY);
		sigmaWizard.addActionListener(listener);
		sigmaPanel.add(sigmaWizard);
		hessianPanel.add(sigmaPanel, hc);
		autoTracePanel.add(hessianPanel, atp_c);
		return autoTracePanel;
	}

	private JPanel hideWindowsPanel() {
		showOrHidePathList = new JButton("Show Path Manager");
		showOrHidePathList.addActionListener(listener);
		showOrHideFillList = new JButton("Show Fill Manager");
		showOrHideFillList.addActionListener(listener);
		final JPanel hideWindowsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		hideWindowsPanel.add(showOrHidePathList, gdb);
		gdb.gridx = 1;
		hideWindowsPanel.add(showOrHideFillList, gdb);
		return hideWindowsPanel;

	}

	private JPanel statusBar() {
		final JPanel statusBar = new JPanel();
		statusBar.setLayout(new BoxLayout(statusBar, BoxLayout.X_AXIS));
		statusBarText = GuiUtils.leftAlignedLabel("Ready to trace...", true);
		statusBarText.setBorder(BorderFactory.createEmptyBorder(0, MARGIN, MARGIN /
			2, 0));
		statusBar.add(statusBarText);
		refreshStatus();
		return statusBar;
	}

	private void refreshStatus() {
		showStatus(null, false);
	}

	/**
	 * Updates the status bar.
	 *
	 * @param msg
	 *            the text to displayed. Set it to null (or empty String) to reset
	 *            the status bar.
	 * @param temporary
	 *            if true and {@code msg} is valid, text is displayed transiently
	 *            for a couple of seconds
	 */
	public void showStatus(final String msg, final boolean temporary) {
		final boolean validMsg = !(msg == null || msg.isEmpty());
		if (validMsg && !temporary) {
			statusBarText.setText(msg);
			return;
		}

		final String defaultText;
		if (plugin.nonInteractiveSession) {
			defaultText = "Analyzing " + plugin.getImagePlus().getTitle();
		}
		else {
			defaultText = "Tracing " + plugin.getImagePlus().getShortTitle() +
				", C=" + plugin.channel + ", T=" + plugin.frame;
		}

		if (!validMsg) {
			statusBarText.setText(defaultText);
			return;
		}

		final Timer timer = new Timer(3000, new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				statusBarText.setText(defaultText);
			}
		});
		timer.setRepeats(false);
		timer.start();
		statusBarText.setText(msg);
	}

	private JPanel getTab() {
		final JPanel tab = new JPanel();
		tab.setBorder(BorderFactory.createEmptyBorder(MARGIN * 2, MARGIN / 2,
			MARGIN / 2, MARGIN));
		tab.setLayout(new GridBagLayout());
		return tab;
	}

	protected void displayOnStarting() {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				if (plugin.prefs.isSaveWinLocations()) arrangeDialogs();
				arrangeCanvases();
				setVisible(true);
				setPathListVisible(true, false);
				setFillListVisible(false);
				plugin.getWindow(MultiDThreePanes.XY_PLANE).toFront();
			}
		});
	}

	private void setSigmaFromUser() {
		final JTextField sigmaField = new JTextField(SNT.formatDouble(getSigma(),
			5), 5);
		final JTextField multiplierField = new JTextField(SNT.formatDouble(
			getMultiplier(), 1), 5);
		final Object[] contents = {
			"<html><b>Sigma</b><br>Enter the approximate radius of the structures you are<br>" +
				"tracing (the default is the minimum voxel separation,<br>i.e., " + SNT
					.formatDouble(plugin.getMinimumSeparation(), 3) + plugin
						.getImagePlus().getCalibration().getUnit() + ")", sigmaField,
			"<html><br><b>Multiplier</b><br>Enter the scaling factor to apply " +
				"(the default is 4.0):", multiplierField, };
		final int result = JOptionPane.showConfirmDialog(this, contents,
			"Select Scale of Traced Structures", JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			final double sigma = GuiUtils.extractDouble(sigmaField);
			final double multiplier = GuiUtils.extractDouble(multiplierField);
			if (Double.isNaN(sigma) || sigma <= 0 || Double.isNaN(multiplier) ||
				multiplier <= 0)
			{
				guiUtils.error("Sigma and multiplier must be positive numbers.",
					"Invalid Input");
				return;
			}
			preprocess.setSelected(false); // should never be on when setSigma is
			// called
			setSigma(sigma, true);
			setMultiplier(multiplier);
		}
	}

	private void arrangeDialogs() {
		Point loc = plugin.prefs.getPathWindowLocation();
		if (loc != null) pmUI.setLocation(loc);
		loc = plugin.prefs.getFillWindowLocation();
		if (loc != null) fmUI.setLocation(loc);
		// final GraphicsDevice activeScreen =
		// getGraphicsConfiguration().getDevice();
		// final int screenWidth = activeScreen.getDisplayMode().getWidth();
		// final int screenHeight = activeScreen.getDisplayMode().getHeight();
		// final Rectangle bounds =
		// activeScreen.getDefaultConfiguration().getBounds();
		//
		// setLocation(bounds.x, bounds.y);
		// pw.setLocation(screenWidth - pw.getWidth(), bounds.y);
		// fw.setLocation(bounds.x + getWidth(), screenHeight - fw.getHeight());
	}

	private void arrangeCanvases() {

		final StackWindow xy_window = plugin.getWindow(MultiDThreePanes.XY_PLANE);
		if (xy_window == null) {
			guiUtils.error("XY view is not available");
			return;
		}
		final GraphicsConfiguration xy_config = xy_window
			.getGraphicsConfiguration();
		final GraphicsDevice xy_screen = xy_config.getDevice();
		final Rectangle xy_screen_bounds = xy_screen.getDefaultConfiguration()
			.getBounds();

		// Center the main tracing canvas on the screen it was found
		final int x = (xy_screen_bounds.width / 2) - (xy_window.getWidth() / 2) +
			xy_screen_bounds.x;
		final int y = (xy_screen_bounds.height / 2) - (xy_window.getHeight() / 2) +
			xy_screen_bounds.y;
		xy_window.setLocation(x, y);

		final StackWindow zy_window = plugin.getWindow(MultiDThreePanes.ZY_PLANE);
		if (zy_window != null) {
			zy_window.setLocation(x + xy_window.getWidth(), y);
			zy_window.toFront();
		}
		final StackWindow xz_window = plugin.getWindow(MultiDThreePanes.XZ_PLANE);
		if (xz_window != null) {
			xz_window.setLocation(x, y + xy_window.getHeight());
			xz_window.toFront();
		}
		xy_window.toFront();
	}

	private void toggleWindowVisibility(final int pane,
		final JCheckBoxMenuItem mItem)
	{
		if (plugin.getImagePlus(pane) == null) {
			String msg;
			if (pane == MultiDThreePanes.XY_PLANE) msg =
				"Tracing image is no longer available.";
			else if (plugin.getSinglePane()) msg =
				"You are tracing in single-pane mode. To generate ZY/XZ " +
					"panes run \"Display ZY/XZ views\".";
			else msg = "Pane was closed and is no longer accessible. " +
				"You can (re)build it using \"Rebuild ZY/XZ views\".";
			guiUtils.error(msg);
			mItem.setSelected(false);
			return;
		}
		// NB: WindowManager list won't be notified
		plugin.getWindow(pane).setVisible(!mItem.isSelected());
	}

	private boolean noPathsError() {
		final boolean noPaths = pathAndFillManager.size() == 0;
		if (noPaths) guiUtils.error("There are no traced paths.");
		return noPaths;
	}

	private void setPathListVisible(final boolean makeVisible,
		final boolean toFront)
	{
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			if (showOrHidePathList != null) showOrHidePathList.setText(
				"  Hide Path Manager");
			pmUI.setVisible(true);
			if (toFront) pmUI.toFront();
		}
		else {
			if (showOrHidePathList != null) showOrHidePathList.setText(
				"Show Path Manager");
			pmUI.setVisible(false);
		}
	}

	private void togglePathListVisibility() {
		assert SwingUtilities.isEventDispatchThread();
		synchronized (pmUI) {
			setPathListVisible(!pmUI.isVisible(), true);
		}
	}

	protected void setFillListVisible(final boolean makeVisible) {
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			if (showOrHideFillList != null) showOrHideFillList.setText(
				"  Hide Fill Manager");
			fmUI.setVisible(true);
			fmUI.toFront();
		}
		else {
			if (showOrHideFillList != null)

				showOrHideFillList.setText("Show Fill Manager");
			fmUI.setVisible(false);
		}
	}

	protected void toggleFillListVisibility() {
		assert SwingUtilities.isEventDispatchThread();
		synchronized (fmUI) {
			setFillListVisible(!fmUI.isVisible());
		}
	}

	protected void thresholdChanged(final double f) {
		fmUI.thresholdChanged(f);
	}

	protected boolean nearbySlices() {
		assert SwingUtilities.isEventDispatchThread();
		return showPartsNearby.isSelected();
	}

	private JMenu helpMenu() {
		final JMenu helpMenu = new JMenu("Help");
		final String URL = "http://imagej.net/Simple_Neurite_Tracer";
		JMenuItem mi = menuItemTrigerringURL("Main documentation page", URL);
		helpMenu.add(mi);
		helpMenu.addSeparator();
		mi = menuItemTrigerringURL("Tutorials", URL + "#Tutorials");
		helpMenu.add(mi);
		mi = menuItemTrigerringURL("Basic instructions", URL +
			":_Basic_Instructions");
		helpMenu.add(mi);
		mi = menuItemTrigerringURL("Step-by-step instructions", URL +
			":_Step-By-Step_Instructions");
		helpMenu.add(mi);
		mi = menuItemTrigerringURL("Filling out processes", URL +
			":_Basic_Instructions#Filling_Out_Neurons");
		helpMenu.add(mi);
		mi = menuItemTrigerringURL("3D interaction", URL + ":_3D_Interaction");
		helpMenu.add(mi);
		mi = menuItemTrigerringURL("Tubular Geodesics", URL +
			":_Tubular_Geodesics");
		helpMenu.add(mi);
		helpMenu.addSeparator();
		mi = menuItemTrigerringURL("List of shortcuts", URL + ":_Key_Shortcuts");
		helpMenu.add(mi);
		helpMenu.addSeparator();
		// mi = menuItemTrigerringURL("Sholl analysis walkthrough", URL +
		// ":_Sholl_analysis");
		// helpMenu.add(mi);
		// helpMenu.addSeparator();
		mi = menuItemTrigerringURL("Ask a question", "http://forum.imagej.net");
		helpMenu.add(mi);
		helpMenu.addSeparator();
		mi = menuItemTrigerringURL("Citing SNT...", URL +
			"#Citing_Simple_Neurite_Tracer");
		helpMenu.add(mi);
		return helpMenu;
	}

	private JMenuItem shollAnalysisHelpMenuItem() {
		JMenuItem mi;
		mi = new JMenuItem("Sholl Analysis...");
		mi.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final Thread newThread = new Thread(new Runnable() {

					@Override
					public void run() {
						if (noPathsError()) return;
						final String modKey = GuiUtils.modKey() + "+Shift";
						final String url1 = Sholl_Analysis.URL +
							"#Analysis_of_Traced_Cells";
						final String url2 =
							"http://imagej.net/Simple_Neurite_Tracer:_Sholl_analysis";
						final StringBuilder sb = new StringBuilder();
						sb.append("<html>");
						sb.append("<div WIDTH=390>");
						sb.append("To initiate <a href='").append(Sholl_Analysis.URL)
							.append("'>Sholl Analysis</a>, ");
						sb.append("you must first select a focal point:");
						sb.append("<ol>");
						sb.append(
							"<li>Mouse over the path of interest. Press \"G\" to activate it</li>");
						sb.append("<li>Press \"").append(modKey).append(
							"\" to select a point along the path</li>");
						sb.append("<li>Press \"").append(modKey).append(
							"+A\" to start analysis</li>");
						sb.append("</ol>");
						sb.append("A detailed walkthrough of this procedure is <a href='")
							.append(url2).append("'>available online</a>. ");
						sb.append("For batch processing, run <a href='").append(url1)
							.append("'>Analyze>Sholl>Sholl Analysis (Tracings)...</a>. ");
						new HTMLDialog("Sholl Analysis How-to", sb.toString(), false);
					}
				});
				newThread.start();
			}
		});
		return mi;
	}

	private JMenuItem menuItemTrigerringURL(final String label,
		final String URL)
	{
		final JMenuItem mi = new JMenuItem(label);
		mi.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(final ActionEvent e) {
				IJ.runPlugIn("ij.plugin.BrowserLauncher", URL);
			}
		});
		return mi;
	}

	/**
	 * Gets the Path Manager frame.
	 *
	 * @return the {@link PathManagerUI} associated with this UI
	 */
	public PathManagerUI getPathManager() {
		return pmUI;
	}

	/**
	 * Gets the Fill Manager frame.
	 *
	 * @return the {@link FillManagerUI} associated with this UI
	 */
	public FillManagerUI getFillManager() {
		return fmUI;
	}

	protected void reset() {
		abortCurrentOperation();
		showStatus("Resetting", true);
		changeState(WAITING_TO_START_PATH);
	}

	protected void abortCurrentOperation() {// FIXME: MOVE TO Simple NeuriteTracer
		switch (currentState) {
			case (SEARCHING):
				updateStatusText("Cancelling path search...", true);
				plugin.cancelSearch(false);
				break;
			case (LOADING_FILTERED_IMAGE):
				updateStatusText("Unloading filtered image", true);
				if (filteredImgLoadingWorker != null) filteredImgLoadingWorker.cancel(
					true);
				plugin.doSearchOnFilteredData = false;
				plugin.tubularGeodesicsTracingEnabled = false;
				plugin.filteredData = null;
				changeState(WAITING_TO_START_PATH);
				break;
			case (CALCULATING_GAUSSIAN):
				updateStatusText("Cancelling Gaussian generation...", true);
				plugin.cancelGaussian();
				break;
			case (WAITING_FOR_SIGMA_POINT):
				showStatus("Sigma adjustment cancelled...", true);
				listener.restorePreSigmaState();
				break;
			case (PARTIAL_PATH):
				showStatus("Last temporary path cancelled...", true);
				plugin.cancelPath();
				break;
			case (QUERY_KEEP):
				showStatus("Last segment cancelled...", true);
				plugin.cancelTemporary();
				break;
			case (FILLING_PATHS):
				showStatus("Filling out cancelled...", true);
				plugin.discardFill(); // will change status
				break;
			case (FITTING_PATHS):
				showStatus("Fitting cancelled...", true);
				pmUI.cancelFit(true);
				break;
			case (PAUSED):
				showStatus("Tracing mode reinstated...", true);
				plugin.pause(false);
				break;
			case (EDITING_MODE):
				showStatus("Tracing mode reinstated...", true);
				plugin.enableEditMode(false);
				break;
			case (WAITING_FOR_SIGMA_CHOICE):
				showStatus("Close the sigma palette to abort sigma input...", true);
				break; // do nothing: Currently we have no control over the sigma
								// palette window
			case (WAITING_TO_START_PATH):
			case (LOADING):
			case (SAVING):
			case (IMAGE_CLOSED):
			case (ANALYSIS_MODE):
				showStatus("Instruction ignored: No task to be aborted", true);
				break; // none of this states needs to be aborted
			default:
				SNT.error("BUG: Wrong state for aborting operation...");
				break;
		}
	}

	private String getState(final int state) {
		switch (state) {
			case WAITING_TO_START_PATH:
				return "WAITING_TO_START_PATH";
			case PARTIAL_PATH:
				return "PARTIAL_PATH";
			case SEARCHING:
				return "SEARCHING";
			case QUERY_KEEP:
				return "QUERY_KEEP";
			// case LOGGING_POINTS:
			// return "LOGGING_POINTS";
			// case DISPLAY_EVS:
			// return "DISPLAY_EVS";
			case FILLING_PATHS:
				return "FILLING_PATHS";
			case CALCULATING_GAUSSIAN:
				return "CALCULATING_GAUSSIAN";
			case WAITING_FOR_SIGMA_POINT:
				return "WAITING_FOR_SIGMA_POINT";
			case WAITING_FOR_SIGMA_CHOICE:
				return "WAITING_FOR_SIGMA_CHOICE";
			case SAVING:
				return "SAVING";
			case LOADING:
				return "LOADING";
			case FITTING_PATHS:
				return "FITTING_PATHS";
			case EDITING_MODE:
				return "EDITING_MODE";
			case PAUSED:
				return "PAUSED";
			case IMAGE_CLOSED:
				return "IMAGE_CLOSED";
			case ANALYSIS_MODE:
				return "ANALYSIS_MODE";
			default:
				return "UNKNOWN";
		}
	}

	protected void togglePathsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		if (showPathsAll.isSelected()) showPathsSelected.setSelected(true);
		else showPathsAll.setSelected(true);
	}

	protected void enableFilteredImgTracing(final boolean enable) {
		if (plugin.isTracingOnFilteredImageAvailable()) {
			if (filteredImgParserChoice.getSelectedIndex() == 0) {
				plugin.doSearchOnFilteredData = enable;
			}
			else if (filteredImgParserChoice.getSelectedIndex() == 1) {
				plugin.tubularGeodesicsTracingEnabled = enable;
			}
			filteredImgActivateCheckbox.setSelected(enable);
		}
		else if (enable) {
			guiUtils.error("Filtered image has not yet been loaded. Please " + (!SNT
				.fileAvailable(plugin.getFilteredImage())
					? "specify the file path of filtered image, then " : "") +
				"initialize its parser.", "Filtered Image Unavailable");
			filteredImgActivateCheckbox.setSelected(false);
			plugin.doSearchOnFilteredData = false;
			updateFilteredFileField();
		}
	}

	protected void toggleFilteredImgTracing() {
		assert SwingUtilities.isEventDispatchThread();
		// Do nothing if we are not allowed to enable FilteredImgTracing
		if (!filteredImgActivateCheckbox.isEnabled()) {
			showStatus("Ignored: Filtered imaged not available", true);
			return;
		}
		enableFilteredImgTracing(!filteredImgActivateCheckbox.isSelected());
	}

	protected void toggleHessian() {
		assert SwingUtilities.isEventDispatchThread();
		if (ignorePreprocessEvents || !preprocess.isEnabled()) return;
		enableHessian(!preprocess.isSelected());
	}

	protected void enableHessian(final boolean enable) {
		if (enable) {
			preGaussianState = currentState;
		}
		else {
			changeState(preGaussianState);
		}
		plugin.enableHessian(enable);
		preprocess.setSelected(enable); // will not trigger ActionEvent
		showStatus("Hessisan " + ((enable) ? "enabled" : "disabled"), true);
	}

	protected void togglePartsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		if (showPartsNearby.isSelected()) showPartsAll.setSelected(true);
		else showPartsNearby.setSelected(true);
	}

	private String hotKeyLabel(final String text, final String key) {
		final String label = text.replaceFirst(key, "<u><b>" + key + "</b></u>");
		return (text.startsWith("<HTML>")) ? label : "<HTML>" + label;
	}

	private class GuiListener implements ActionListener, ItemListener,
		SigmaPalette.SigmaPaletteListener, ImageListener
	{

		private final static String EDIT_SIGMA_MANUALLY = "Manually...";
		private final static String EDIT_SIGMA_VISUALLY = "Visually...";
		private int preSigmaPaletteState;

		public GuiListener() {
			ImagePlus.addImageListener(this);
		}

		/* ImageListener */
		@Override
		public void imageClosed(final ImagePlus imp) {
			// updateColorImageChoice(); //FIXME
			if (plugin.getImagePlus() == imp) changeState(
				SNTUI.IMAGE_CLOSED);
		}

		/* (non-Javadoc)
		 * @see ij.ImageListener#imageOpened(ij.ImagePlus)
		 */
		@Override
		public void imageOpened(final ImagePlus imp) {}

		/* (non-Javadoc)
		 * @see ij.ImageListener#imageUpdated(ij.ImagePlus)
		 */
		@Override
		public void imageUpdated(final ImagePlus imp) {}

		/* (non-Javadoc)
		 * @see tracing.gui.SigmaPalette.SigmaPaletteListener#sigmaPaletteOKed(double, double)
		 */
		/* SigmaPaletteListener */
		@Override
		public void sigmaPaletteOKed(final double newSigma,
			final double newMultiplier)
		{
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					changeState(preSigmaPaletteState);
					setMultiplier(newMultiplier);
					setSigma(newSigma, true);
				}
			});
		}

		/* (non-Javadoc)
		 * @see tracing.gui.SigmaPalette.SigmaPaletteListener#sigmaPaletteCanceled()
		 */
		@Override
		public void sigmaPaletteCanceled() {
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					restorePreSigmaState();
				}
			});
		}

		/* (non-Javadoc)
		 * @see java.awt.event.ItemListener#itemStateChanged(java.awt.event.ItemEvent)
		 */
		@Override
		public void itemStateChanged(final ItemEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == showPartsNearby) {
				plugin.justDisplayNearSlices(showPartsNearby.isSelected(),
					(int) nearbyFieldSpinner.getValue());
			}
			else if (source == showPartsAll) {
				plugin.justDisplayNearSlices(!showPartsAll.isSelected(),
					(int) nearbyFieldSpinner.getValue());
			}
			else if (source == useSnapWindow) {
				plugin.enableSnapCursor(useSnapWindow.isSelected());
			}
			else if (source == showPathsSelected) {
				plugin.setShowOnlySelectedPaths(showPathsSelected.isSelected());
			}
			else if (source == showPathsAll) {
				plugin.setShowOnlySelectedPaths(!showPathsAll.isSelected());
			}
		}

		/* (non-Javadoc)
		 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(final ActionEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == preprocess) {
				enableHessian(preprocess.isSelected());
			}
			else if (source == saveMenuItem && !noPathsError()) {

				final File suggestedFile = SNT.findClosestPair(plugin.prefs
					.getRecentFile(), "traces");
				final File saveFile = guiUtils.saveFile("Save traces as...",
					suggestedFile, Collections.singletonList(".traces"));
				if (saveFile == null) return; // user pressed cancel;
				if (saveFile.exists() && !guiUtils.getConfirmation("The file " +
					saveFile.getAbsolutePath() + " already exists.\n" +
					"Do you want to replace it?", "Override traces file?"))
				{
					return;
				}

				showStatus("Saving traces to " + saveFile.getAbsolutePath(), false);

				final int preSavingState = currentState;
				changeState(SAVING);
				try {
					pathAndFillManager.writeXML(saveFile.getAbsolutePath(),
						plugin.useCompressedXML);
				}
				catch (final IOException ioe) {
					showStatus("Saving failed.", true);
					guiUtils.error("Writing traces to '" + saveFile.getAbsolutePath() +
						"' failed. See Console for details.");
					changeState(preSavingState);
					ioe.printStackTrace();
					return;
				}
				changeState(preSavingState);
				showStatus("Saving completed.", true);

				plugin.unsavedPaths = false;

			}
			else if (source == loadTracesMenuItem || source == loadSWCMenuItem) {

				if (plugin.pathsUnsaved() && !guiUtils.getConfirmation(
					"There are unsaved paths. Do you really want to load new traces?",
					"Warning")) return;
				final int preLoadingState = currentState;
				changeState(LOADING);
				if (source == loadTracesMenuItem) plugin.loadTracesFile();
				else plugin.loadSWCFile();
				changeState(preLoadingState);

			}
			else if (source == exportAllSWCMenuItem && !noPathsError()) {

				if (pathAndFillManager.usingNonPhysicalUnits() && !guiUtils
					.getConfirmation(
						"These tracings were obtained from a spatially uncalibrated " +
							"image but the SWC specification assumes all coordinates to be " +
							"in " + GuiUtils.micrometre() +
							". Do you really want to proceed " + "with the SWC export?",
						"Warning")) return;

				final File suggestedFile = SNT.findClosestPair(plugin.loadedImageFile(),
					".swc)");
				final File saveFile = guiUtils.saveFile("Export All Paths as SWC...",
					suggestedFile, Collections.singletonList(".swc"));
				if (saveFile == null) return; // user pressed cancel
				if (saveFile.exists()) {
					if (!guiUtils.getConfirmation("The file " + saveFile
						.getAbsolutePath() + " already exists.\n" +
						"Do you want to replace it?", "Override SWC file?")) return;
				}
				final String savePath = saveFile.getAbsolutePath();
				SNT.log("Exporting paths to " + saveFile);
				if (!pathAndFillManager.checkOKToWriteAllAsSWC(savePath)) return;
				pathAndFillManager.exportAllAsSWC(savePath);

			}
			else if (source == exportCSVMenuItem && !noPathsError()) {

				final File suggestedFile = SNT.findClosestPair(plugin.loadedImageFile(),
					".csv)");
				final File saveFile = guiUtils.saveFile("Export All Paths as CSV...",
					suggestedFile, Collections.singletonList(".csv"));
				if (saveFile == null) return; // user pressed cancel
				if (saveFile.exists()) {
					if (!guiUtils.getConfirmation("The file " + saveFile
						.getAbsolutePath() + " already exists.\n" +
						"Do you want to replace it?", "Override CSV file?")) return;
				}
				final String savePath = saveFile.getAbsolutePath();
				showStatus("Exporting as CSV to " + savePath, false);

				final int preExportingState = currentState;
				changeState(SAVING);
				// Export here...
				try {
					pathAndFillManager.exportToCSV(saveFile);
				}
				catch (final IOException ioe) {
					showStatus("Exporting failed.", true);
					guiUtils.error("Writing traces to '" + savePath +
						"' failed. See Console for details.");
					changeState(preExportingState);
					ioe.printStackTrace();
					return;
				}
				showStatus("Export complete.", true);
				changeState(preExportingState);

			}
			else if (source == measureMenuItem && !noPathsError()) {
				final Tree tree = new Tree(pathAndFillManager.getPathsFiltered());
				tree.setLabel("All Paths");
				final TreeAnalyzer ta = new TreeAnalyzer(tree);
				ta.setContext(plugin.getContext());
				ta.setTable(pmUI.getTable(), PathManagerUI.TABLE_TITLE);
				ta.run();
				return;
			}
			else if (source == strahlerMenuItem && !noPathsError()) {
				final StrahlerCmd sa = new StrahlerCmd(new Tree(pathAndFillManager
					.getPathsFiltered()));
				sa.setContext(plugin.getContext());
				sa.setTable(new DefaultGenericTable(),
					"SNT: Horton-Strahler Analysis (All Paths)");
				sa.run();
				return;
			}
			else if (source == plotMenuItem && !noPathsError()) {
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", new Tree(pathAndFillManager.getPathsFiltered()));
				input.put("title", (plugin.getImagePlus() == null) ? "All Paths"
					: plugin.getImagePlus().getTitle());
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(PlotterCmd.class, true, input);
				return;
			}
			else if (source == loadLabelsMenuItem) {

				final File suggestedFile = SNT.findClosestPair(plugin.loadedImageFile(),
					".labels)");
				final File saveFile = guiUtils.openFile("Select Labels File...",
					suggestedFile, Collections.singletonList("labels"));
				if (saveFile == null) return; // user pressed cancel;
				if (saveFile.exists()) {
					if (!guiUtils.getConfirmation("The file " + saveFile
						.getAbsolutePath() + " already exists.\n" +
						"Do you want to replace it?", "Override SWC file?")) return;
				}
				if (saveFile != null) {
					plugin.loadLabelsFile(saveFile.getAbsolutePath());
					return;
				}

			}
			else if (source == abortButton) {

				abortCurrentOperation();
			}
			else if (source == keepSegment) {

				plugin.confirmTemporary();

			}
			else if (source == junkSegment) {

				plugin.cancelTemporary();

			}
			else if (source == completePath) {

				plugin.finishedPath();

			}
			else if (source == quitMenuItem) {

				exitRequested();

			}
			else if (source == showOrHidePathList) {

				togglePathListVisibility();

			}
			else if (source == showOrHideFillList) {

				toggleFillListVisibility();

			}
			else if (e.getActionCommand().equals(EDIT_SIGMA_MANUALLY)) {

				setSigmaFromUser();

			}
			else if (e.getActionCommand().equals(EDIT_SIGMA_VISUALLY)) {

				preSigmaPaletteState = currentState;
				changeState(WAITING_FOR_SIGMA_POINT);
				plugin.setCanvasLabelAllPanes("Choosing Sigma");
			}

			else if (source == colorImageChoice) {

				if (!ignoreColorImageChoiceEvents) checkForColorImageChange();

			}
		}

		private void restorePreSigmaState() {
			changeState(preSigmaPaletteState);
			plugin.setCanvasLabelAllPanes(null);
		}
	}

}