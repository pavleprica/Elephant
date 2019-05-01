package com.pinktwins.elephant;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import javax.swing.undo.UndoManager;

import org.apache.commons.io.IOUtils;

import com.google.common.eventbus.Subscribe;
import com.pinktwins.elephant.BrowserPane.BrowserEventListener;
import com.pinktwins.elephant.data.Note;
import com.pinktwins.elephant.data.Settings;
import com.pinktwins.elephant.eventbus.StyleCommandEvent;
import com.pinktwins.elephant.eventbus.ToastEvent;
import com.pinktwins.elephant.eventbus.UndoRedoStateUpdateRequest;
import com.pinktwins.elephant.ui.AutoIndentAction;
import com.pinktwins.elephant.ui.HomeAction;
import com.pinktwins.elephant.ui.PasswordDialog;
import com.pinktwins.elephant.ui.ShiftTabAction;
import com.pinktwins.elephant.ui.TabAction;
import com.pinktwins.elephant.util.CryptoUtil;
import com.pinktwins.elephant.util.CustomMouseListener;
import com.pinktwins.elephant.util.Factory;
import com.pinktwins.elephant.util.IOUtil;
import com.pinktwins.elephant.util.ResizeListener;
import com.pinktwins.elephant.util.RtfUtil;
import com.pinktwins.elephant.util.TextComponentUtil;

public class CustomEditor extends RoundPanel {

	private static final Logger LOG = Logger.getLogger(CustomEditor.class.getName());

	public static final String ELEM = AbstractDocument.ElementNameAttribute;
	public static final String ICON = StyleConstants.IconElementName;
	public static final String COMP = StyleConstants.ComponentElementName;

	private JTextField title;
	private CustomTextPane note;
	private HtmlPane htmlPane;
	private BrowserPane browserPane;
	private JPanel padding;

	private UndoManager undoManager = new UndoManager();

	private final PasswordDialog passwordDialog = new PasswordDialog();
	private final CryptoUtil cryptoUtil = new CryptoUtil();

	public boolean isRichText, isMarkdown;
	private boolean maybeImporting;

	private int frozenSelectionStart, frozenSelectionEnd;

	final Color kDividerColor = Color.decode("#dbdbdb");

	private final FocusListener editorFocusListener = new FocusListener() {
		@Override
		public void focusGained(FocusEvent e) {
			if (eeListener != null) {
				eeListener.editingFocusGained();
			}
		}

		@Override
		public void focusLost(FocusEvent e) {
			if (eeListener != null) {
				eeListener.editingFocusLost();
			}
		}
	};

	private final AbstractAction boldAction = new AbstractAction() {
		StyledEditorKit.BoldAction a = new StyledEditorKit.BoldAction();

		@Override
		public void actionPerformed(ActionEvent e) {
			if (!isMarkdown) {
				isRichText = true;
				a.actionPerformed(e);
			} else {
				markdownStyleCommand("**", "**");
			}
		}
	};

	private final AbstractAction italicAction = new AbstractAction() {
		StyledEditorKit.ItalicAction a = new StyledEditorKit.ItalicAction();

		@Override
		public void actionPerformed(ActionEvent e) {
			if (!isMarkdown) {
				isRichText = true;
				a.actionPerformed(e);
			} else {
				markdownStyleCommand("_", "_");
			}
		}
	};

	private final AbstractAction underlineAction = new AbstractAction() {
		StyledEditorKit.UnderlineAction a = new StyledEditorKit.UnderlineAction();

		@Override
		public void actionPerformed(ActionEvent e) {
			if (!isMarkdown) {
				isRichText = true;
				a.actionPerformed(e);
			} else {
				markdownStyleCommand("<u>", "</u>");
			}
		}
	};

	private final AbstractAction strikethroughAction = new AbstractAction() {
		@Override
		public void actionPerformed(ActionEvent e) {
			if (!isMarkdown) {
				StyledEditorKit kit = (StyledEditorKit) note.getEditorKit();
				MutableAttributeSet as = kit.getInputAttributes();
				boolean b = (StyleConstants.isStrikeThrough(as)) ? false : true;
				StyleConstants.setStrikeThrough(as, b);
				note.setCharacterAttributes(as, false);

				isRichText = true;
			} else {
				markdownStyleCommand("<strike>", "</strike>");
			}
		}
	};

	// Rearrange lines based on strikethrough. ST lines will 'fall' to bottom of document.
	private final AbstractAction strikethroughRearrangeAction = new AbstractAction() {
		@Override
		public void actionPerformed(ActionEvent event) {
			if (isRichText) {
				try {
					Document doc = note.getDocument();
					int pos = doc.getLength() - 1;
					int insertPoint = pos;

					for (; pos >= 0; pos--) {
						String s = doc.getText(pos > 0 ? pos - 1 : pos, 1);
						if ("\n".equals(s) || pos == 0) {
							AttributeSet as = getAttributes(pos);
							if (StyleConstants.isStrikeThrough(as)) {
								s = doc.getText(pos, doc.getLength() - pos).split("\n")[0];
								int len = s.length();
								doc.insertString(insertPoint, "\n", null);
								doc.insertString(insertPoint, s, as);
								doc.remove(pos > 0 ? pos - 1 : pos, len + 1);
								insertPoint -= len + 1;
							}
						}
					}
				} catch (BadLocationException e) {
					LOG.severe("Fail: " + e);
				}
			}
		}
	};

	private final AbstractAction increaseFontSizeAction = new AbstractAction() {
		@Override
		public void actionPerformed(ActionEvent e) {
			shiftFontSize(1);
		}
	};

	private final AbstractAction decreaseFontSizeAction = new AbstractAction() {
		@Override
		public void actionPerformed(ActionEvent e) {
			shiftFontSize(-1);
		}
	};

	private EditorEventListener eeListener;
	private NoteAttachmentTransferHandler attachmentTransferHandler;

	class NoteAttachmentTransferHandler extends AttachmentTransferHandler {
		public NoteAttachmentTransferHandler(EditorEventListener listener) {
			super(listener);
		}

		@Override
		public boolean canImport(TransferHandler.TransferSupport info) {
			if (isShowingMarkdown()) {
				return false;
			}

			maybeImporting = true;
			return true;
		}
	}

	private static class CustomDocument extends DefaultStyledDocument {
		private static final long serialVersionUID = 2807153134148093523L;

		@Override
		public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
			str = str.replaceAll("\t", "    ");
			super.insertString(offs, str, a);
		}
	}

	private final CustomMouseListener paddingClick = new CustomMouseListener() {
		@Override
		public void mouseClicked(MouseEvent e) {
			startEditing();
		}
	};

	public void startEditing() {
		if (isMarkdown && isShowingMarkdown()) {
			switchToMarkdownEditor();
		}
		note.requestFocusInWindow();
	}

	public boolean isRichText() {
		return isRichText;
	}

	public void setMarkdown(boolean b) {
		isMarkdown = b;
	}

	public boolean maybeImporting() {
		return maybeImporting;
	}

	public JTextPane getTextPane() {
		return note;
	}

	public HtmlPane getHtmlPane() {
		return htmlPane;
	}

	public JTextPane getEditorPane() {
		return isMarkdown ? getHtmlPane() : getTextPane();
	}

	public void setEditorEventListener(EditorEventListener l) {
		eeListener = l;
		attachmentTransferHandler = new NoteAttachmentTransferHandler(eeListener);
	}

	public CustomEditor() {
		super();

		Elephant.eventBus.register(this);

		this.setDoubleBuffered(true);

		setBackground(Color.WHITE);
		setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 18));
		setLayout(new BorderLayout());

		JPanel titlePanel = new JPanel();
		titlePanel.setLayout(new BorderLayout());
		titlePanel.setBackground(kDividerColor);
		titlePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));

		title = new JTextField();
		title.setFont(ElephantWindow.fontEditorTitle);
		title.setBorder(BorderFactory.createEmptyBorder(2, 0, 12, 0));
		title.addFocusListener(editorFocusListener);
		TextComponentUtil.insertListenerForHintText(title, "Untitled");

		final KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();

		title.addKeyListener(new KeyListener() {

			@Override
			public void keyTyped(KeyEvent e) {
			}

			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					manager.focusNextComponent();
					if (isMarkdown) {
						startEditing();
						initialFocus();
					}
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
			}
		});

		titlePanel.add(title, BorderLayout.CENTER);

		title.setText("");
		add(titlePanel, BorderLayout.NORTH);

		createNote();

		// resize padding so note is at least kMinNoteSize height
		addComponentListener(new ResizeListener() {
			@Override
			public void componentResized(ComponentEvent e) {
				int h = getHeight();
				int needed = NoteEditor.kMinNoteSize - h;
				int preferred = h + needed - (padding.getLocation().y + 12);

				if (needed > 0) {
					if (preferred < 0) {
						createPadding();
						preferred = 10;
					}
					revalidate();
				} else {
					padding.setVisible(false);

					if (preferred > 0) {
						createPadding();
					}
				}

				if (preferred > 0) {
					padding.setVisible(true);
					padding.setPreferredSize(new Dimension(10, preferred));
				} else {
					padding.setVisible(false);
				}
			}
		});
	}

	public void cleanup() {
		Elephant.eventBus.unregister(this);
	}

	public void resetTitleFont() {
		title.setFont(ElephantWindow.fontEditorTitle);
	}

	void insertNewline(int position) {
		try {
			note.getDocument().insertString(position, "\n", null);
		} catch (BadLocationException e) {
			LOG.severe("Fail: " + e);
		}
	}

	AttributeSet getAttributes(int position) {
		return ((CustomDocument) note.getDocument()).getCharacterElement(position).getAttributes();
	}

	public void encryptSelection() {
		String text = note.getSelectedText();
		if (text != null && !text.isEmpty()) {

			String pw = passwordDialog.getPassword();
			if (pw != null && !pw.isEmpty()) {

				try {
					String enc = cryptoUtil.encryptToBase64(pw, text);
					note.setClipboardContents(enc);

					new ToastEvent("Encrypted to clipboard").post();
				} catch (IOException e) {
					LOG.severe("Failed encrypting text.");
					new ToastEvent("Failed encrypting text.").post();
				}
				return;
			} else {
				new ToastEvent("No password").post();
			}
		} else {
			new ToastEvent("No selection").post();
		}
	}

	public void decryptSelection() {
		String pw = passwordDialog.getPassword();
		if (pw != null && !pw.isEmpty()) {
			String text = note.getSelectedText();

			if (text == null) {
				// No selection? Check if this note has encrypted data
				text = findEncryptedText();
			}

			if (text != null && !text.isEmpty()) {
				try {
					String dec = cryptoUtil.decryptBase64(pw, text);
					if (dec == null) {
						new ToastEvent("No ecrypted data found").post();
						return;
					} else {
						note.setClipboardContents(dec);
					}

					new ToastEvent("Decrypted to clipboard").post();
				} catch (Exception e) {
					new ToastEvent("Decryption failed. Wrong password?").post();
				}
				return;
			} else {
				new ToastEvent("No selection").post();
			}
		} else {
			new ToastEvent("No password").post();
		}
	}

	private String findEncryptedText() {
		// Find first line starting with 'encv0:'
		try {
			String s = getText();
			String[] arr = s.split("\n");
			for (String line : arr) {
				if (line.indexOf("encv0:") == 0) {
					return line;
				}
			}
		} catch (BadLocationException e) {
		}
		return null;
	}

	class CustomTextPane extends JTextPane implements ClipboardOwner {

		// http://www.javapractices.com/topic/TopicAction.do?Id=82

		public void setClipboardContents(String aString) {
			StringSelection stringSelection = new StringSelection(aString);
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(stringSelection, this);
		}

		public void setClipboardContentsRtf(final String rtf, final String plainText) {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

			/*
			 * older way. Now using DataFlavors, below.
			 * 
			 * try { DataHandler hand = new DataHandler(new ByteArrayInputStream(rtf.getBytes("UTF-8")), "text/rtf");
			 * clipboard.setContents(hand, this); } catch (UnsupportedEncodingException e) { LOG.severe("Fail: " + e); }
			 */

			clipboard.setContents(new Transferable() {
				private final DataFlavor flavors[] = { new DataFlavor("text/rtf", "Rich Formatted Text"), new DataFlavor("text/plain", "Plain Text") };

				@Override
				public DataFlavor[] getTransferDataFlavors() {
					return flavors;
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor f) {
					return f.getMimeType().contains("text/rtf") || f.getMimeType().contains("text/plain");
				}

				@Override
				public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException, IOException {
					if (f.getMimeType().contains("text/rtf")) {
						return new ByteArrayInputStream(rtf.getBytes(IOUtil.getCharset()));
					} else if (f.getMimeType().contains("text/plain")) {
						return new ByteArrayInputStream(plainText.getBytes(IOUtil.getCharset()));
					} else {
						throw new UnsupportedFlavorException(f);
					}
				}
			}, this);
		}

		// Returns Image or String
		public Object getClipboardContents() {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			// odd: the Object param of getContents is not currently used
			Transferable contents = clipboard.getContents(null);

			DataFlavor[] fl = contents.getTransferDataFlavors();
			List<DataFlavor> textFlavors = Factory.newArrayList();
			List<DataFlavor> imageFlavors = Factory.newArrayList();
			for (DataFlavor df : fl) {
				String mime = df.getMimeType();
				if (mime.indexOf("text/rtf") >= 0 || mime.indexOf("text/plain") >= 0) {
					textFlavors.add(df);
				}
				if (mime.indexOf("image/x-java-image") >= 0) {
					imageFlavors.add(df);
				}
			}

			if (textFlavors.size() == 0 && imageFlavors.size() > 0) {
				Image img;
				try {
					img = (Image) contents.getTransferData(imageFlavors.get(0));
				} catch (UnsupportedFlavorException e) {
					LOG.severe("FAIL: unsupported clipboard flavor");
					return "";
				} catch (IOException e) {
					e.printStackTrace();
					LOG.severe("FAIL: failed to read clipboard image");
					return "";
				}
				return img;
			}

			String result = "";

			DataFlavor[] te = new DataFlavor[textFlavors.size()];
			te = textFlavors.toArray(te);

			DataFlavor best = DataFlavor.selectBestTextFlavor(te);
			boolean hasTransferableText = (contents != null) && contents.isDataFlavorSupported(best);
			if (hasTransferableText) {
				try {
					Reader r = best.getReaderForText(contents);
					BufferedReader br = new BufferedReader(r);
					result = IOUtils.toString(br);
				} catch (UnsupportedFlavorException e) {
					LOG.severe("Fail: " + e);
				} catch (IOException e) {
					LOG.severe("Fail: " + e);
				}
			}

			return result;
		}

		private class CPPInfo {
			Document doc;
			int pos, len, start, end, selLen, adjust;
			boolean hasSelection;

			public CPPInfo() {
				doc = getDocument();
				pos = getCaretPosition();
				len = doc.getLength();
				start = getSelectionStart();
				end = getSelectionEnd();
				selLen = end - start;
				adjust = 0;
				if (pos >= end) {
					adjust = end - start;
				}
				hasSelection = (start >= 0 && start < len && end > start && end <= len);
			}
		}

		@Override
		public void cut() {
			copy();
			CPPInfo i = new CPPInfo();
			if (i.hasSelection) {
				try {
					i.doc.remove(i.start, i.selLen);
				} catch (BadLocationException e) {
					LOG.severe("Fail: " + e);
				}
			}
		}

		@Override
		public void copy() {
			String s = getSelectedText();
			if (s != null && !s.isEmpty()) {
				setClipboardContents(s);
			}

			if (isRichText) {
				int start = note.getSelectionStart();
				int end = note.getSelectionEnd();

				if (end > start) {
					// Put rtf to clipboard: clone document, remove
					// everything but selection.
					Document d = new CustomDocument();
					try {
						RtfUtil.putRtf(d, RtfUtil.getRtf(getDocument()), 0);
						d.remove(end, d.getLength() - end);
						d.remove(0, start);
						String rtf = RtfUtil.getRtf(d);

						setClipboardContentsRtf(rtf, s);
					} catch (IOException e) {
						LOG.severe("Fail: " + e);
					} catch (BadLocationException e) {
						LOG.severe("Fail: " + e);
					}
				}
			}
		}

		void pasteImage(Image image) {
			ElephantWindow w = ElephantWindow.getActiveWindow();
			if (w != null) {
				w.pasteImageFromClipboard(image);
			} else {
				LOG.severe("FAIL: failed to find current ElephantWindow.");
			}
		}

		void pasteString(String s) {
			if (!s.isEmpty()) {
				try {
					CPPInfo i = new CPPInfo();

					if (i.hasSelection) {
						i.doc.remove(i.start, i.selLen);
					}

					i.pos -= i.adjust;

					if (s.length() < 5 || !"{\\rtf".equals(s.substring(0, 5))) {
						i.doc.insertString(i.pos, s, null);
					} else {

						// Clipboard has rich text content.
						// If markdown note, paste as plain text.
						// If pastePlaintext setting is true, paste plain text on plain text notes,
						// and rich text on rich text notes.

						boolean pastePlain = ElephantWindow.plainTextOnPasteAction;

						if (pastePlain || isMarkdown || (Elephant.settings.getBoolean(Settings.Keys.PASTE_PLAINTEXT) && !isRichText)) {
							String plain = Note.plainTextContents(s);

							// There seems to be an extra linefeed there but should we remove it or not?
							/*
							 * if (plain != null && plain.length() > 0 && plain.charAt(plain.length() - 1) == '\n') {
							 * plain = plain.substring(0, plain.length() - 1); }
							 */

							i.doc.insertString(i.pos, plain, null);
							return;
						}

						// RTFEditorKit doesn't support 'position' argument on
						// read() method, so create a new document and copy
						// text + styles over.
						CustomDocument d = new CustomDocument();

						try {
							RtfUtil.putRtf(d, s, 0);

							Element[] elems = d.getRootElements();
							for (Element e : elems) {
								for (int idx = 0, count = e.getElementCount(); idx < count - 1; idx++) {
									Element sub = e.getElement(idx);
									if ("paragraph".equals(sub.getName())) {
										int start = sub.getStartOffset();
										int end = sub.getEndOffset();
										AttributeSet as = d.getCharacterElement(start).getAttributes();

										if (end > start) {
											String text = d.getText(start, end - start);
											i.doc.insertString(i.pos, text, as);
											i.pos += end - start;
											isRichText = true;
										}
									}
								}
							}
						} catch (IOException e) {
							LOG.severe("Fail: " + e);
						}
					}
				} catch (BadLocationException e) {
					LOG.severe("Fail: " + e);
				}
			}
		}

		@Override
		public void paste() {
			Object o = getClipboardContents();
			if (o instanceof Image) {
				pasteImage((Image) o);
				return;
			}

			if (o instanceof String) {
				pasteString((String) o);
				return;
			}

			LOG.severe("FAIL: clipboard error");
		}

		@Override
		public void lostOwnership(Clipboard clipboard, Transferable contents) {
		}
	}

	private void createNote() {

		if (note != null) {
			try {
				note.getDocument().remove(0, note.getDocument().getLength());
			} catch (BadLocationException e1) {
				e1.printStackTrace();
			}
			note.removeAll();
			remove(note);
		}

		if (htmlPane != null) {
			remove(htmlPane);
			htmlPane = null;
		}

		if (browserPane != null && browserPane.getParent() != null) {
			remove(browserPane);
			browserPane.clear();
		}

		note = new CustomTextPane();
		if (Elephant.settings.getWordWrap()) {
			note.setEditorKit(new WrappedEditorKit());
		}
		note.setDocument(new CustomDocument());
		note.addFocusListener(editorFocusListener);
		note.setFont(ElephantWindow.fontEditor);
		note.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		note.setDragEnabled(true);

		// enable AutoIndent as described at http://www.jroller.com/santhosh/entry/autoindent_for_jtextarea
		if (Elephant.settings.getAutoBullet()) {
			note.registerKeyboardAction(new AutoIndentAction(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
		}

		// enable Tab and Shift-Tab behavior for bullet lists
		note.registerKeyboardAction(new TabAction(), KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), JComponent.WHEN_FOCUSED);
		note.registerKeyboardAction(new ShiftTabAction(), KeyStroke.getKeyStroke(KeyEvent.VK_TAB, java.awt.event.InputEvent.SHIFT_DOWN_MASK),
				JComponent.WHEN_FOCUSED);

		note.registerKeyboardAction(new HomeAction(), KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), JComponent.WHEN_FOCUSED);

		maybeImporting = false;

		note.setTransferHandler(attachmentTransferHandler);

		note.setCaret(new SelectionPreservingCaret());

		note.addCaretListener(new CaretListener() {
			@Override
			public void caretUpdate(CaretEvent e) {
				if (eeListener != null) {
					eeListener.caretChanged(note);
				}
			}
		});

		note.getDocument().addUndoableEditListener(new UndoEditListener());

		note.addMouseListener(new AttachmentDragMouseListener(this, note) {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (eeListener != null && attachmentObject != null) {
					eeListener.attachmentClicked(event, attachmentObject);
				} else {
					if (SwingUtilities.isLeftMouseButton(event)) {
						startEditing();
					} else if (SwingUtilities.isMiddleMouseButton(event)) {
						if (note.isFocusOwner()) {
							getTextPane().paste();
						}
					}
				}
			}

			@Override
			public void attachmentMoved(AttachmentInfo info) {
				if (eeListener != null) {
					eeListener.attachmentMoved(info);
				}
			}
		});

		InputMap inputMap = note.getInputMap();

		KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_B, ElephantWindow.menuMask);
		inputMap.put(ks, boldAction);
		ks = KeyStroke.getKeyStroke(KeyEvent.VK_I, ElephantWindow.menuMask);
		inputMap.put(ks, italicAction);
		ks = KeyStroke.getKeyStroke(KeyEvent.VK_U, ElephantWindow.menuMask);
		inputMap.put(ks, underlineAction);
		ks = KeyStroke.getKeyStroke(KeyEvent.VK_K, ElephantWindow.menuMask | KeyEvent.CTRL_DOWN_MASK);
		inputMap.put(ks, strikethroughAction);
		ks = KeyStroke.getKeyStroke(KeyEvent.VK_K, ElephantWindow.menuMask | KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
		inputMap.put(ks, strikethroughRearrangeAction);

		ks = KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, ElephantWindow.menuMask);
		inputMap.put(ks, increaseFontSizeAction);

		ks = KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, ElephantWindow.menuMask | KeyEvent.SHIFT_DOWN_MASK);
		inputMap.put(ks, increaseFontSizeAction);

		ks = KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, ElephantWindow.menuMask);
		inputMap.put(ks, decreaseFontSizeAction);

		add(note, BorderLayout.CENTER);

		createPadding();
	}

	public void saveSelection() {
		if (note != null) {
			frozenSelectionStart = note.getSelectionStart();
			frozenSelectionEnd = note.getSelectionEnd();
		}
	}

	public void restoreSelection() {
		if (note != null && frozenSelectionStart != frozenSelectionEnd) {
			note.setSelectionStart(frozenSelectionStart);
			note.setSelectionEnd(frozenSelectionEnd);
			((DefaultCaret) note.getCaret()).setSelectionVisible(true);
		}
	}

	private void markdownStyleCommand(String codeStart, String codeEnd) {
		int lenStart = codeStart.length();
		int lenEnd = codeEnd.length();

		if (note.getSelectionStart() == note.getSelectionEnd()) {
			try {
				note.getDocument().insertString(note.getCaretPosition(), codeStart + codeEnd, null);
				note.setCaretPosition(note.getCaretPosition() - lenEnd);
			} catch (BadLocationException e) {
				LOG.severe("Fail: " + e);
			}
		} else {
			try {
				int codeEnding = Math.max(note.getSelectionStart() + lenStart, note.getSelectionEnd());
				boolean codeCouldFit = codeEnding < note.getDocument().getLength();

				if (codeCouldFit && note.getText(note.getSelectionStart(), lenStart).equals(codeStart)
						&& note.getText(note.getSelectionEnd() - lenEnd, lenEnd).equals(codeEnd)) {
					note.getDocument().remove(note.getSelectionEnd() - lenEnd, lenEnd);
					note.getDocument().remove(note.getSelectionStart(), lenStart);
				} else {
					note.getDocument().insertString(note.getSelectionEnd(), codeEnd, null);
					note.getDocument().insertString(note.getSelectionStart(), codeStart, null);
					note.setSelectionStart(note.getSelectionStart() - lenStart);
				}
			} catch (BadLocationException e) {
				LOG.severe("Fail: " + e);
			}
		}
	}

	private void shiftFontSize(final int delta) {
		if (!isMarkdown) {
			StyledEditorKit kit = (StyledEditorKit) note.getEditorKit();
			MutableAttributeSet as = kit.getInputAttributes();
			int size = StyleConstants.getFontSize(as);
			StyleConstants.setFontSize(as, size + delta);
			note.setCharacterAttributes(as, false);

			isRichText = true;
		} else {
			try {
				String s = note.getText(0, note.getCaretPosition());
				int lastLf = s.lastIndexOf("\n");
				if (lastLf == -1) {
					lastLf = 0;
				} else {
					lastLf++;
				}
				if (delta > 0) {
					note.getDocument().insertString(lastLf, "#", null);
				} else {
					System.out.println(lastLf);
					if (s.length() > lastLf && s.charAt(lastLf) == '#') {
						note.getDocument().remove(lastLf, 1);
					}
				}
			} catch (BadLocationException e) {
				LOG.severe("Fail: " + e);
			}
		}
	}

	@Subscribe
	public void handleStyleCommandEvent(StyleCommandEvent e) {
		String cmd = e.event.getActionCommand();
		if ("Bold".equals(cmd)) {
			boldAction.actionPerformed(e.event);
			return;
		}
		if ("Italic".equals(cmd)) {
			italicAction.actionPerformed(e.event);
			return;
		}
		if ("Underline".equals(cmd)) {
			underlineAction.actionPerformed(e.event);
			return;
		}
		if ("Strikethrough".equals(cmd)) {
			strikethroughAction.actionPerformed(e.event);
			return;
		}
		if ("Bigger".equals(cmd)) {
			increaseFontSizeAction.actionPerformed(e.event);
			return;
		}
		if ("Smaller".equals(cmd)) {
			decreaseFontSizeAction.actionPerformed(e.event);
			return;
		}
		if ("Make Plain Text".equals(cmd)) {
			// handled directly from ElephantWindow -> noteEditor
		}
	}

	public void turnToPlainText() {
		note.getStyledDocument().setCharacterAttributes(0, note.getDocument().getLength(), new SimpleAttributeSet(), true);
		note.requestFocusInWindow();
		isRichText = false;
		isMarkdown = false;
	}

	private void createPadding() {
		if (padding == null) {
			padding = new JPanel(null);
			padding.setBackground(Color.WHITE);
			padding.addMouseListener(paddingClick);
			padding.setPreferredSize(new Dimension(0, 0));
			add(padding, BorderLayout.SOUTH);
		}
	}

	public void setTitle(String s) {
		title.setText(s);
		title.setCaretPosition(0);
		title.setSelectionEnd(0);
	}

	public static boolean setTextRtfOrPlain(JTextPane textPane, String s) {
		boolean rich = false;

		if (s != null && s.length() > 0) {
			if (s.indexOf("{\\rtf") == 0) {
				try {
					RtfUtil.putRtf(textPane.getDocument(), s, 0);
					if (textPane.getDocument().getLength() == 0) {
						textPane.setText(s);
					} else {
						rich = true;
					}
				} catch (IOException e) {
					LOG.severe("Fail: " + e);
					textPane.setText(s);
				} catch (BadLocationException e) {
					LOG.severe("Fail: " + e);
					textPane.setText(s);
				}
			} else {
				textPane.setText(s);
			}
		}

		return rich;
	}

	public void setText(String s) {
		note.setText("");

		isRichText = setTextRtfOrPlain(note, s);

		note.setCaretPosition(0);
	}

	public String getTitle() {
		return title.getText();
	}

	public String getText() throws BadLocationException {
		Document doc = note.getDocument();
		String plain = doc.getText(0, doc.getLength());
		String rtf = RtfUtil.getRtf(doc);

		return rtf != null && rtf.length() > 0 && isRichText ? rtf : plain;
	}

	public void clear() {
		setTitle("");

		// replace JTextPane with new instance to get rid of old styles.
		createNote();
		discardUndoBuffer();
	}

	public boolean hasFocus() {
		return note.hasFocus() || title.hasFocus();
	}

	public void initialFocus() {
		note.setCaretPosition(0);
		note.requestFocusInWindow();
	}

	public void focusTitle() {
		title.setCaretPosition(0);
		title.requestFocusInWindow();
	}

	class AttachmentInfo {
		Object object;
		int startPosition;
		int endPosition;
	}

	public List<AttachmentInfo> getAttachmentInfo() {
		List<AttachmentInfo> list = Factory.newArrayList();

		ElementIterator iterator = new ElementIterator(note.getDocument());
		Element element;
		while ((element = iterator.next()) != null) {
			AttributeSet as = element.getAttributes();
			if (as.containsAttribute(ELEM, ICON)) {
				AttachmentInfo info = new AttachmentInfo();
				info.object = StyleConstants.getIcon(as);
				info.startPosition = element.getStartOffset();
				info.endPosition = element.getEndOffset();
				list.add(info);
			}

			if (as.containsAttribute(ELEM, COMP)) {
				AttachmentInfo info = new AttachmentInfo();
				info.object = StyleConstants.getComponent(as);
				info.startPosition = element.getStartOffset();
				info.endPosition = element.getEndOffset();
				list.add(info);
			}
		}

		return list;
	}

	// remove icon/file elements from document.
	// Returns list with correct element positions
	// after removal for possible reimport.

	public List<AttachmentInfo> removeAttachmentElements(List<AttachmentInfo> info) {
		List<AttachmentInfo> info_reverse = new ArrayList<AttachmentInfo>(info);

		Collections.reverse(info_reverse);
		for (AttachmentInfo i : info_reverse) {
			int tagLen = i.endPosition - i.startPosition;
			if (tagLen < 5) { // might be unneccessary safety
				try {
					getTextPane().getDocument().remove(i.startPosition, tagLen);

					// Correct attachment position in the document
					// WITHOUT
					// any attachment element markers:
					int n = info.indexOf(i);
					i.startPosition -= n;
					i.endPosition -= n;
				} catch (BadLocationException e) {
					LOG.severe("Fail: " + e);
				}
			}
		}

		return info_reverse;
	}

	protected class UndoEditListener implements UndoableEditListener {
		public void undoableEditHappened(UndoableEditEvent e) {
			// Remember the edit and update the menus
			undoManager.addEdit(e.getEdit());
			new UndoRedoStateUpdateRequest(undoManager).post();
		}
	}

	public void undo() {
		if (undoManager.canUndo()) {
			undoManager.undo();
		}
		new UndoRedoStateUpdateRequest(undoManager).post();
	}

	public void redo() {
		if (undoManager.canRedo()) {
			undoManager.redo();
		}
		new UndoRedoStateUpdateRequest(undoManager).post();
	}

	public void discardUndoBuffer() {
		undoManager.discardAllEdits();
		new UndoRedoStateUpdateRequest(undoManager).post();
	}

	public void updateUndoState() {
		new UndoRedoStateUpdateRequest(undoManager).post();
	}

	private void switchToMarkdownEditor() {
		if (isMarkdown && isShowingMarkdown()) {
			remove(htmlPane);
			add(note, BorderLayout.CENTER);
			revalidate();
			htmlPane = null;
		}
	}

	public void displayHtml(final File noteFile, final String html) {
		if (htmlPane == null) {
			htmlPane = new HtmlPane(noteFile, new Runnable() {
				@Override
				public void run() {
					// Executed when mouseClick does not open a link
					// -> go to edit mode
					CustomEditor.this.switchToMarkdownEditor();
				}
			});
		}

		htmlPane.setText(html);

		remove(note);
		add(htmlPane, BorderLayout.CENTER);
	}

	public boolean isShowingMarkdown() {
		return htmlPane != null;
	}

	public void displayBrowser(final File noteFile) {
		if (browserPane == null) {
			browserPane = new BrowserPane();
			browserPane.setBrowserEventListener(new BrowserEventListener() {
				@Override
				public void mouseWheelEvent(MouseWheelEvent e) {
					Container c = CustomEditor.this.getParent();
					c.dispatchEvent(e);
				}
			});
		}

		try {
			URL url = noteFile.toURI().toURL();
			browserPane.loadURL(url.toExternalForm());

			remove(note);
			add(browserPane, BorderLayout.CENTER);
		} catch (MalformedURLException e) {
			LOG.severe("Fail: " + e);
			clear();
		}
	}
}
