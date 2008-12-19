//
//  Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
//  Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
//  Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
//  Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
//  Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
//
//  This is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This software is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this software; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
//  USA.
//

package com.tightvnc.vncviewer;

import com.tightvnc.decoder.CoRREDecoder;
import com.tightvnc.decoder.HextileDecoder;
import com.tightvnc.decoder.RREDecoder;
import com.tightvnc.decoder.RawDecoder;
import com.tightvnc.decoder.TightDecoder;
import com.tightvnc.decoder.ZRLEDecoder;
import com.tightvnc.decoder.ZlibDecoder;
import com.tightvnc.decoder.common.Repaintable;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.lang.*;
import java.util.zip.*;


//
// VncCanvas is a subclass of Canvas which draws a VNC desktop on it.
//

class VncCanvas extends Canvas
  implements KeyListener, MouseListener, MouseMotionListener, RecordInterface,
             Repaintable {

  VncViewer viewer;
  RfbProto rfb;
  ColorModel cm8, cm24;
  Color[] colors;
  int bytesPixel;

  int maxWidth = 0, maxHeight = 0;
  int scalingFactor;
  int scaledWidth, scaledHeight;

  Image memImage;
  Graphics memGraphics;

  Image rawPixelsImage;
  MemoryImageSource pixelsSource;
  byte[] pixels8;
  int[] pixels24;

  //
  // Decoders
  //

  RawDecoder rawDecoder;
  RREDecoder rreDecoder;
  CoRREDecoder correDecoder;
  ZlibDecoder zlibDecoder;
  HextileDecoder hextileDecoder;
  ZRLEDecoder zrleDecoder;
  TightDecoder tightDecoder;

  // Base decoder decoders array
  RawDecoder []decoders = null;

  // Update statistics.
  long statStartTime;           // time on first framebufferUpdateRequest
  int statNumUpdates;           // counter for FramebufferUpdate messages
  int statNumTotalRects;        // rectangles in FramebufferUpdate messages
  int statNumPixelRects;        // the same, but excluding pseudo-rectangles
  int statNumRectsTight;        // Tight-encoded rectangles (including JPEG)
  int statNumRectsTightJPEG;    // JPEG-compressed Tight-encoded rectangles
  int statNumRectsZRLE;         // ZRLE-encoded rectangles
  int statNumRectsHextile;      // Hextile-encoded rectangles
  int statNumRectsRaw;          // Raw-encoded rectangles
  int statNumRectsCopy;         // CopyRect rectangles
  int statNumBytesEncoded;      // number of bytes in updates, as received
  int statNumBytesDecoded;      // number of bytes, as if Raw encoding was used

  // ZRLE encoder's data.
  byte[] zrleBuf;
  int zrleBufLen = 0;
  byte[] zrleTilePixels8;
  int[] zrleTilePixels24;
  ZlibInStream zrleInStream;
  boolean zrleRecWarningShown = false;
  boolean isFirstSizeAutoUpdate = true;

  // Zlib encoder's data.
  byte[] zlibBuf;
  int zlibBufLen = 0;
  Inflater zlibInflater;

  // True if we process keyboard and mouse events.
  boolean inputEnabled;

  //
  // The constructors.
  //

  public VncCanvas(VncViewer v, int maxWidth_, int maxHeight_)
    throws IOException {

    viewer = v;
    maxWidth = maxWidth_;
    maxHeight = maxHeight_;

    rfb = viewer.rfb;
    scalingFactor = viewer.options.scalingFactor;

    cm8 = new DirectColorModel(8, 7, (7 << 3), (3 << 6));
    cm24 = new DirectColorModel(24, 0xFF0000, 0x00FF00, 0x0000FF);

    colors = new Color[256];
    for (int i = 0; i < 256; i++)
      colors[i] = new Color(cm8.getRGB(i));

    //
    // Create decoders
    //

    // Input stream for decoders
    RfbInputStream rfbis = new RfbInputStream(rfb);

    rawDecoder = new RawDecoder(memGraphics, rfbis);
    rreDecoder = new RREDecoder(memGraphics, rfbis);
    correDecoder = new CoRREDecoder(memGraphics, rfbis);
    hextileDecoder = new HextileDecoder(memGraphics, rfbis);
    tightDecoder = new TightDecoder(memGraphics, rfbis);
    zlibDecoder = new ZlibDecoder(memGraphics, rfbis);
    zrleDecoder = new ZRLEDecoder(memGraphics, rfbis);

    //
    // Set data for decoders that needs extra parameters
    //

    hextileDecoder.setRepainableControl(this);
    tightDecoder.setRepainableControl(this);

    //
    // Create array that contains our decoders
    //

    decoders = new RawDecoder[7];
    decoders[0] = rawDecoder;
    decoders[1] = rreDecoder;
    decoders[2] = correDecoder;
    decoders[3] = hextileDecoder;
    decoders[4] = zlibDecoder;
    decoders[5] = tightDecoder;
    decoders[6] = zrleDecoder;

    //
    // Set session recorder for decoders
    //

    for (int i = 0; i < decoders.length; i++) {
      decoders[i].setSessionRecorder(this);
    }
    
    setPixelFormat();

    resetSelection();

    inputEnabled = false;
    if (!viewer.options.viewOnly)
      enableInput(true);

    // Enable mouse and keyboard event listeners.
    addKeyListener(this);
    addMouseListener(this);
    addMouseMotionListener(this);
  }

  public VncCanvas(VncViewer v) throws IOException {
    this(v, 0, 0);
  }

  //
  // Callback methods to determine geometry of our Component.
  //

  public Dimension getPreferredSize() {
    return new Dimension(scaledWidth, scaledHeight);
  }

  public Dimension getMinimumSize() {
    return new Dimension(scaledWidth, scaledHeight);
  }

  public Dimension getMaximumSize() {
    return new Dimension(scaledWidth, scaledHeight);
  }

  //
  // All painting is performed here.
  //

  public void update(Graphics g) {
    paint(g);
  }

  public void paint(Graphics g) {
    synchronized(memImage) {
      if (rfb.framebufferWidth == scaledWidth) {
        g.drawImage(memImage, 0, 0, null);
      } else {
        paintScaledFrameBuffer(g);
      }
    }
    if (showSoftCursor) {
      int x0 = cursorX - hotX, y0 = cursorY - hotY;
      Rectangle r = new Rectangle(x0, y0, cursorWidth, cursorHeight);
      if (r.intersects(g.getClipBounds())) {
	g.drawImage(softCursor, x0, y0, null);
      }
    }
    if (isInSelectionMode()) {
      Rectangle r = getSelection(true);
      if (r.width > 0 && r.height > 0) {
        // Don't forget to correct the coordinates for the right and bottom
        // borders, so that the borders are the part of the selection.
        r.width -= 1;
        r.height -= 1;
        g.setXORMode(Color.yellow);
        g.drawRect(r.x, r.y, r.width, r.height);
      }
    }
  }

  public void paintScaledFrameBuffer(Graphics g) {
    g.drawImage(memImage, 0, 0, scaledWidth, scaledHeight, null);
  }

  //
  // Start/stop receiving mouse events. Keyboard events are received
  // even in view-only mode, because we want to map the 'r' key to the
  // screen refreshing function.
  //

  public synchronized void enableInput(boolean enable) {
    if (enable && !inputEnabled) {
      inputEnabled = true;
      if (viewer.showControls) {
	viewer.buttonPanel.enableRemoteAccessControls(true);
      }
      createSoftCursor();	// scaled cursor
    } else if (!enable && inputEnabled) {
      inputEnabled = false;
      if (viewer.showControls) {
	viewer.buttonPanel.enableRemoteAccessControls(false);
      }
      createSoftCursor();	// non-scaled cursor
    }
  }

  public void setPixelFormat() throws IOException {
    if (viewer.options.eightBitColors) {
      rfb.writeSetPixelFormat(8, 8, false, true, 7, 7, 3, 0, 3, 6);
      bytesPixel = 1;
    } else {
      rfb.writeSetPixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0);
      bytesPixel = 4;
    }
    updateFramebufferSize();
  }

  void setScalingFactor(int sf) {
    scalingFactor = sf;
    updateFramebufferSize();
    invalidate();
  }

  void updateFramebufferSize() {

    // Useful shortcuts.
    int fbWidth = rfb.framebufferWidth;
    int fbHeight = rfb.framebufferHeight;

    // FIXME: This part of code must be in VncViewer i think
    if (viewer.options.autoScale) {
      if (viewer.inAnApplet) {
        maxWidth = viewer.getWidth();
        maxHeight = viewer.getHeight();
      } else {
        if (viewer.vncFrame != null) {
          if (isFirstSizeAutoUpdate) {
            isFirstSizeAutoUpdate = false;
            Dimension screenSize = viewer.vncFrame.getToolkit().getScreenSize();
            maxWidth = (int)screenSize.getWidth() - 100;
            maxHeight = (int)screenSize.getHeight() - 100;
            viewer.vncFrame.setSize(maxWidth, maxHeight);
          } else {
            viewer.desktopScrollPane.doLayout();
            maxWidth = viewer.desktopScrollPane.getWidth();
            maxHeight = viewer.desktopScrollPane.getHeight();
          }
        } else {
          maxWidth = fbWidth;
          maxHeight = fbHeight;
        }
      }
      int f1 = maxWidth * 100 / fbWidth;
      int f2 = maxHeight * 100 / fbHeight;
      scalingFactor = Math.min(f1, f2);
      if (scalingFactor > 100)
	scalingFactor = 100;
      System.out.println("Scaling desktop at " + scalingFactor + "%");
    }

    // Update scaled framebuffer geometry.
    scaledWidth = (fbWidth * scalingFactor + 50) / 100;
    scaledHeight = (fbHeight * scalingFactor + 50) / 100;

    // Create new off-screen image either if it does not exist, or if
    // its geometry should be changed. It's not necessary to replace
    // existing image if only pixel format should be changed.
    if (memImage == null) {
      memImage = viewer.vncContainer.createImage(fbWidth, fbHeight);
      memGraphics = memImage.getGraphics();
    } else if (memImage.getWidth(null) != fbWidth ||
	       memImage.getHeight(null) != fbHeight) {
      synchronized(memImage) {
	memImage = viewer.vncContainer.createImage(fbWidth, fbHeight);
	memGraphics = memImage.getGraphics();
      }
    }

    //
    // Update decoders
    //

    //
    // FIXME: Why decoders can be null here?
    //

    if (decoders != null) {
      for (int i = 0; i < decoders.length; i++) {
        //
        // Set changes to every decoder that we can use
        //

        decoders[i].setBPP(bytesPixel);
        decoders[i].setFrameBufferSize(fbWidth, fbHeight);
        decoders[i].setGraphics(memGraphics);

        //
        // Update decoder
        //

        decoders[i].update();
      }
    }

    // Images with raw pixels should be re-allocated on every change
    // of geometry or pixel format.
    if (bytesPixel == 1) {

      pixels24 = null;
      pixels8 = new byte[fbWidth * fbHeight];

      pixelsSource =
	new MemoryImageSource(fbWidth, fbHeight, cm8, pixels8, 0, fbWidth);

      zrleTilePixels24 = null;
      zrleTilePixels8 = new byte[64 * 64];

    } else {

      pixels8 = null;
      pixels24 = new int[fbWidth * fbHeight];

      pixelsSource =
	new MemoryImageSource(fbWidth, fbHeight, cm24, pixels24, 0, fbWidth);

      zrleTilePixels8 = null;
      zrleTilePixels24 = new int[64 * 64];

    }
    pixelsSource.setAnimated(true);
    rawPixelsImage = Toolkit.getDefaultToolkit().createImage(pixelsSource);

    // FIXME: This part of code must be in VncViewer i think
    // Update the size of desktop containers.
    if (viewer.inSeparateFrame) {
      if (viewer.desktopScrollPane != null) {
        if (!viewer.options.autoScale) {
          resizeDesktopFrame();
        } else {
          setSize(scaledWidth, scaledHeight);
          viewer.desktopScrollPane.setSize(maxWidth + 200,
                                           maxHeight + 200);
        }
      }
    } else {
      setSize(scaledWidth, scaledHeight);
    }
    viewer.moveFocusToDesktop();
  }

  void resizeDesktopFrame() {
    setSize(scaledWidth, scaledHeight);

    // FIXME: Find a better way to determine correct size of a
    // ScrollPane.  -- const
    Insets insets = viewer.desktopScrollPane.getInsets();
    viewer.desktopScrollPane.setSize(scaledWidth +
				     2 * Math.min(insets.left, insets.right),
				     scaledHeight +
				     2 * Math.min(insets.top, insets.bottom));

    viewer.vncFrame.pack();

    // Try to limit the frame size to the screen size.

    Dimension screenSize = viewer.vncFrame.getToolkit().getScreenSize();
    Dimension frameSize = viewer.vncFrame.getSize();
    Dimension newSize = frameSize;

    // Reduce Screen Size by 30 pixels in each direction;
    // This is a (poor) attempt to account for
    //     1) Menu bar on Macintosh (should really also account for
    //        Dock on OSX).  Usually 22px on top of screen.
    //     2) Taxkbar on Windows (usually about 28 px on bottom)
    //     3) Other obstructions.

    screenSize.height -= 30;
    screenSize.width  -= 30;

    boolean needToResizeFrame = false;
    if (frameSize.height > screenSize.height) {
      newSize.height = screenSize.height;
      needToResizeFrame = true;
    }
    if (frameSize.width > screenSize.width) {
      newSize.width = screenSize.width;
      needToResizeFrame = true;
    }
    if (needToResizeFrame) {
      viewer.vncFrame.setSize(newSize);
    }

    viewer.desktopScrollPane.doLayout();
  }

  //
  // processNormalProtocol() - executed by the rfbThread to deal with the
  // RFB socket.
  //

  public void processNormalProtocol() throws Exception {

    // Start/stop session recording if necessary.
    viewer.checkRecordingStatus();

    rfb.writeFramebufferUpdateRequest(0, 0, rfb.framebufferWidth,
				      rfb.framebufferHeight, false);

    if (viewer.options.continuousUpdates) {
      rfb.tryEnableContinuousUpdates(0, 0, rfb.framebufferWidth,
                                     rfb.framebufferHeight);
    }

    resetStats();
    boolean statsRestarted = false;

    //
    // main dispatch loop
    //

    while (true) {

      // Read message type from the server.
      int msgType = rfb.readServerMessageType();

      // Process the message depending on its type.
      switch (msgType) {
      case RfbProto.FramebufferUpdate:

        if (statNumUpdates == viewer.debugStatsExcludeUpdates &&
            !statsRestarted) {
          resetStats();
          statsRestarted = true;
        } else if (statNumUpdates == viewer.debugStatsMeasureUpdates &&
                   statsRestarted) {
          viewer.disconnect();
        }

	rfb.readFramebufferUpdate();
	statNumUpdates++;

	boolean cursorPosReceived = false;

	for (int i = 0; i < rfb.updateNRects; i++) {

	  rfb.readFramebufferUpdateRectHdr();
	  statNumTotalRects++;
	  int rx = rfb.updateRectX, ry = rfb.updateRectY;
	  int rw = rfb.updateRectW, rh = rfb.updateRectH;

	  if (rfb.updateRectEncoding == rfb.EncodingLastRect)
	    break;

	  if (rfb.updateRectEncoding == rfb.EncodingNewFBSize) {
	    rfb.setFramebufferSize(rw, rh);
	    updateFramebufferSize();
	    break;
	  }

	  if (rfb.updateRectEncoding == rfb.EncodingXCursor ||
	      rfb.updateRectEncoding == rfb.EncodingRichCursor) {
	    handleCursorShapeUpdate(rfb.updateRectEncoding, rx, ry, rw, rh);
	    continue;
	  }

	  if (rfb.updateRectEncoding == rfb.EncodingPointerPos) {
	    softCursorMove(rx, ry);
	    cursorPosReceived = true;
	    continue;
	  }

          long numBytesReadBefore = rfb.getNumBytesRead();

          rfb.startTiming();

	  switch (rfb.updateRectEncoding) {
	  case RfbProto.EncodingRaw:
	    statNumRectsRaw++;
	    handleRawRect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingCopyRect:
	    statNumRectsCopy++;
	    handleCopyRect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingRRE:
	    handleRRERect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingCoRRE:
	    handleCoRRERect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingHextile:
	    statNumRectsHextile++;
	    handleHextileRect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingZRLE:
	    statNumRectsZRLE++;
	    handleZRLERect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingZlib:
            handleZlibRect(rx, ry, rw, rh);
	    break;
	  case RfbProto.EncodingTight:
            if (tightDecoder != null) {
	      statNumRectsTightJPEG = tightDecoder.getNumJPEGRects();
            }
	    handleTightRect(rx, ry, rw, rh);
	    break;
	  default:
	    throw new Exception("Unknown RFB rectangle encoding " +
				rfb.updateRectEncoding);
	  }

          rfb.stopTiming();

          statNumPixelRects++;
          statNumBytesDecoded += rw * rh * bytesPixel;
          statNumBytesEncoded +=
            (int)(rfb.getNumBytesRead() - numBytesReadBefore);
	}

	boolean fullUpdateNeeded = false;

	// Start/stop session recording if necessary. Request full
	// update if a new session file was opened.
	if (viewer.checkRecordingStatus())
	  fullUpdateNeeded = true;

	// Defer framebuffer update request if necessary. But wake up
	// immediately on keyboard or mouse event. Also, don't sleep
	// if there is some data to receive, or if the last update
	// included a PointerPos message.
	if (viewer.deferUpdateRequests > 0 &&
	    rfb.available() == 0 && !cursorPosReceived) {
	  synchronized(rfb) {
	    try {
	      rfb.wait(viewer.deferUpdateRequests);
	    } catch (InterruptedException e) {
	    }
	  }
	}

        viewer.autoSelectEncodings();

	// Before requesting framebuffer update, check if the pixel
	// format should be changed.
	if (viewer.options.eightBitColors != (bytesPixel == 1)) {
          // Pixel format should be changed.
          if (!rfb.continuousUpdatesAreActive()) {
            // Continuous updates are not used. In this case, we just
            // set new pixel format and request full update.
            setPixelFormat();
            fullUpdateNeeded = true;
          } else {
            // Otherwise, disable continuous updates first. Pixel
            // format will be set later when we are sure that there
            // will be no unsolicited framebuffer updates.
            rfb.tryDisableContinuousUpdates();
            break; // skip the code below
          }
	}

        // Enable/disable continuous updates to reflect the GUI setting.
        boolean enable = viewer.options.continuousUpdates;
        if (enable != rfb.continuousUpdatesAreActive()) {
          if (enable) {
            rfb.tryEnableContinuousUpdates(0, 0, rfb.framebufferWidth,
                                           rfb.framebufferHeight);
          } else {
            rfb.tryDisableContinuousUpdates();
          }
        }

        // Finally, request framebuffer update if needed.
        if (fullUpdateNeeded) {
          rfb.writeFramebufferUpdateRequest(0, 0, rfb.framebufferWidth,
                                            rfb.framebufferHeight, false);
        } else if (!rfb.continuousUpdatesAreActive()) {
          rfb.writeFramebufferUpdateRequest(0, 0, rfb.framebufferWidth,
                                            rfb.framebufferHeight, true);
        }

	break;

      case RfbProto.SetColourMapEntries:
	throw new Exception("Can't handle SetColourMapEntries message");

      case RfbProto.Bell:
        Toolkit.getDefaultToolkit().beep();
	break;

      case RfbProto.ServerCutText:
	String s = rfb.readServerCutText();
	viewer.clipboard.setCutText(s);
	break;

      case RfbProto.EndOfContinuousUpdates:
        if (rfb.continuousUpdatesAreActive()) {
          rfb.endOfContinuousUpdates();

          // Change pixel format if such change was pending. Note that we
          // could not change pixel format while continuous updates were
          // in effect.
          boolean incremental = true;
          if (viewer.options.eightBitColors != (bytesPixel == 1)) {
            setPixelFormat();
            incremental = false;
          }
          // From this point, we ask for updates explicitly.
          rfb.writeFramebufferUpdateRequest(0, 0, rfb.framebufferWidth,
                                            rfb.framebufferHeight,
                                            incremental);
        }
        break;

      default:
	throw new Exception("Unknown RFB message type " + msgType);
      }
    }
  }


  //
  // Handle a raw rectangle. The second form with paint==false is used
  // by the Hextile decoder for raw-encoded tiles.
  //

  void handleRawRect(int x, int y, int w, int h) throws IOException, Exception {
    handleRawRect(x, y, w, h, true);
  }

  void handleRawRect(int x, int y, int w, int h, boolean paint)
    throws IOException , Exception{
    rawDecoder.handleRect(x, y, w, h);
    if (paint)
      scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a CopyRect rectangle.
  //

  void handleCopyRect(int x, int y, int w, int h) throws IOException {

    rfb.readCopyRect();
    memGraphics.copyArea(rfb.copyRectSrcX, rfb.copyRectSrcY, w, h,
			 x - rfb.copyRectSrcX, y - rfb.copyRectSrcY);

    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle an RRE-encoded rectangle.
  //

  void handleRRERect(int x, int y, int w, int h) throws IOException {
    rreDecoder.handleRect(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a CoRRE-encoded rectangle.
  //

  void handleCoRRERect(int x, int y, int w, int h) throws IOException {
    correDecoder.handleRect(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a Hextile-encoded rectangle.
  //

  // These colors should be kept between handleHextileSubrect() calls.
  private Color hextile_bg, hextile_fg;

  void handleHextileRect(int x, int y, int w, int h) throws IOException,
                                                            Exception {
    hextileDecoder.handleRect(x, y, w, h);
  }

  //
  // Handle one tile in the Hextile-encoded data.
  //

  void handleHextileSubrect(int tx, int ty, int tw, int th)
    throws IOException, Exception {

    int subencoding = rfb.readU8();
    if (rfb.rec != null) {
      rfb.rec.writeByte(subencoding);
    }

    // Is it a raw-encoded sub-rectangle?
    if ((subencoding & rfb.HextileRaw) != 0) {
      handleRawRect(tx, ty, tw, th, false);
      return;
    }

    // Read and draw the background if specified.
    byte[] cbuf = new byte[bytesPixel];
    if ((subencoding & rfb.HextileBackgroundSpecified) != 0) {
      rfb.readFully(cbuf);
      if (bytesPixel == 1) {
	hextile_bg = colors[cbuf[0] & 0xFF];
      } else {
	hextile_bg = new Color(cbuf[2] & 0xFF, cbuf[1] & 0xFF, cbuf[0] & 0xFF);
      }
      if (rfb.rec != null) {
	rfb.rec.write(cbuf);
      }
    }
    memGraphics.setColor(hextile_bg);
    memGraphics.fillRect(tx, ty, tw, th);

    // Read the foreground color if specified.
    if ((subencoding & rfb.HextileForegroundSpecified) != 0) {
      rfb.readFully(cbuf);
      if (bytesPixel == 1) {
	hextile_fg = colors[cbuf[0] & 0xFF];
      } else {
	hextile_fg = new Color(cbuf[2] & 0xFF, cbuf[1] & 0xFF, cbuf[0] & 0xFF);
      }
      if (rfb.rec != null) {
	rfb.rec.write(cbuf);
      }
    }

    // Done with this tile if there is no sub-rectangles.
    if ((subencoding & rfb.HextileAnySubrects) == 0)
      return;

    int nSubrects = rfb.readU8();
    int bufsize = nSubrects * 2;
    if ((subencoding & rfb.HextileSubrectsColoured) != 0) {
      bufsize += nSubrects * bytesPixel;
    }
    byte[] buf = new byte[bufsize];
    rfb.readFully(buf);
    if (rfb.rec != null) {
      rfb.rec.writeByte(nSubrects);
      rfb.rec.write(buf);
    }

    int b1, b2, sx, sy, sw, sh;
    int i = 0;

    if ((subencoding & rfb.HextileSubrectsColoured) == 0) {

      // Sub-rectangles are all of the same color.
      memGraphics.setColor(hextile_fg);
      for (int j = 0; j < nSubrects; j++) {
	b1 = buf[i++] & 0xFF;
	b2 = buf[i++] & 0xFF;
	sx = tx + (b1 >> 4);
	sy = ty + (b1 & 0xf);
	sw = (b2 >> 4) + 1;
	sh = (b2 & 0xf) + 1;
	memGraphics.fillRect(sx, sy, sw, sh);
      }
    } else if (bytesPixel == 1) {

      // BGR233 (8-bit color) version for colored sub-rectangles.
      for (int j = 0; j < nSubrects; j++) {
	hextile_fg = colors[buf[i++] & 0xFF];
	b1 = buf[i++] & 0xFF;
	b2 = buf[i++] & 0xFF;
	sx = tx + (b1 >> 4);
	sy = ty + (b1 & 0xf);
	sw = (b2 >> 4) + 1;
	sh = (b2 & 0xf) + 1;
	memGraphics.setColor(hextile_fg);
	memGraphics.fillRect(sx, sy, sw, sh);
      }

    } else {

      // Full-color (24-bit) version for colored sub-rectangles.
      for (int j = 0; j < nSubrects; j++) {
	hextile_fg = new Color(buf[i+2] & 0xFF,
			       buf[i+1] & 0xFF,
			       buf[i] & 0xFF);
	i += 4;
	b1 = buf[i++] & 0xFF;
	b2 = buf[i++] & 0xFF;
	sx = tx + (b1 >> 4);
	sy = ty + (b1 & 0xf);
	sw = (b2 >> 4) + 1;
	sh = (b2 & 0xf) + 1;
	memGraphics.setColor(hextile_fg);
	memGraphics.fillRect(sx, sy, sw, sh);
      }

    }
  }

  //
  // Handle a ZRLE-encoded rectangle.
  //
  // FIXME: Currently, session recording is not fully supported for ZRLE.
  //

  void handleZRLERect(int x, int y, int w, int h) throws Exception {

    if (zrleInStream == null)
      zrleInStream = new ZlibInStream();

    int nBytes = rfb.readU32();
    if (nBytes > 64 * 1024 * 1024)
      throw new Exception("ZRLE decoder: illegal compressed data size");

    if (zrleBuf == null || zrleBufLen < nBytes) {
      zrleBufLen = nBytes + 4096;
      zrleBuf = new byte[zrleBufLen];
    }

    // FIXME: Do not wait for all the data before decompression.
    rfb.readFully(zrleBuf, 0, nBytes);

    if (rfb.rec != null) {
      if (rfb.recordFromBeginning) {
        rfb.rec.writeIntBE(nBytes);
        rfb.rec.write(zrleBuf, 0, nBytes);
      } else if (!zrleRecWarningShown) {
        System.out.println("Warning: ZRLE session can be recorded" +
                           " only from the beginning");
        System.out.println("Warning: Recorded file may be corrupted");
        zrleRecWarningShown = true;
      }
    }

    zrleInStream.setUnderlying(new MemInStream(zrleBuf, 0, nBytes), nBytes);

    for (int ty = y; ty < y+h; ty += 64) {

      int th = Math.min(y+h-ty, 64);

      for (int tx = x; tx < x+w; tx += 64) {

        int tw = Math.min(x+w-tx, 64);

        int mode = zrleInStream.readU8();
        boolean rle = (mode & 128) != 0;
        int palSize = mode & 127;
        int[] palette = new int[128];

        readZrlePalette(palette, palSize);

        if (palSize == 1) {
          int pix = palette[0];
          Color c = (bytesPixel == 1) ?
            colors[pix] : new Color(0xFF000000 | pix);
          memGraphics.setColor(c);
          memGraphics.fillRect(tx, ty, tw, th);
          continue;
        }

        if (!rle) {
          if (palSize == 0) {
            readZrleRawPixels(tw, th);
          } else {
            readZrlePackedPixels(tw, th, palette, palSize);
          }
        } else {
          if (palSize == 0) {
            readZrlePlainRLEPixels(tw, th);
          } else {
            readZrlePackedRLEPixels(tw, th, palette);
          }
        }
        handleUpdatedZrleTile(tx, ty, tw, th);
      }
    }

    zrleInStream.reset();

    scheduleRepaint(x, y, w, h);
  }

  int readPixel(InStream is) throws Exception {
    int pix;
    if (bytesPixel == 1) {
      pix = is.readU8();
    } else {
      int p1 = is.readU8();
      int p2 = is.readU8();
      int p3 = is.readU8();
      pix = (p3 & 0xFF) << 16 | (p2 & 0xFF) << 8 | (p1 & 0xFF);
    }
    return pix;
  }

  void readPixels(InStream is, int[] dst, int count) throws Exception {
    int pix;
    if (bytesPixel == 1) {
      byte[] buf = new byte[count];
      is.readBytes(buf, 0, count);
      for (int i = 0; i < count; i++) {
        dst[i] = (int)buf[i] & 0xFF;
      }
    } else {
      byte[] buf = new byte[count * 3];
      is.readBytes(buf, 0, count * 3);
      for (int i = 0; i < count; i++) {
        dst[i] = ((buf[i*3+2] & 0xFF) << 16 |
                  (buf[i*3+1] & 0xFF) << 8 |
                  (buf[i*3] & 0xFF));
      }
    }
  }

  void readZrlePalette(int[] palette, int palSize) throws Exception {
    readPixels(zrleInStream, palette, palSize);
  }

  void readZrleRawPixels(int tw, int th) throws Exception {
    if (bytesPixel == 1) {
      zrleInStream.readBytes(zrleTilePixels8, 0, tw * th);
    } else {
      readPixels(zrleInStream, zrleTilePixels24, tw * th); ///
    }
  }

  void readZrlePackedPixels(int tw, int th, int[] palette, int palSize)
    throws Exception {

    int bppp = ((palSize > 16) ? 8 :
                ((palSize > 4) ? 4 : ((palSize > 2) ? 2 : 1)));
    int ptr = 0;

    for (int i = 0; i < th; i++) {
      int eol = ptr + tw;
      int b = 0;
      int nbits = 0;

      while (ptr < eol) {
        if (nbits == 0) {
          b = zrleInStream.readU8();
          nbits = 8;
        }
        nbits -= bppp;
        int index = (b >> nbits) & ((1 << bppp) - 1) & 127;
        if (bytesPixel == 1) {
          zrleTilePixels8[ptr++] = (byte)palette[index];
        } else {
          zrleTilePixels24[ptr++] = palette[index];
        }
      }
    }
  }

  void readZrlePlainRLEPixels(int tw, int th) throws Exception {
    int ptr = 0;
    int end = ptr + tw * th;
    while (ptr < end) {
      int pix = readPixel(zrleInStream);
      int len = 1;
      int b;
      do {
        b = zrleInStream.readU8();
        len += b;
      } while (b == 255);

      if (!(len <= end - ptr))
        throw new Exception("ZRLE decoder: assertion failed" +
                            " (len <= end-ptr)");

      if (bytesPixel == 1) {
        while (len-- > 0) zrleTilePixels8[ptr++] = (byte)pix;
      } else {
        while (len-- > 0) zrleTilePixels24[ptr++] = pix;
      }
    }
  }

  void readZrlePackedRLEPixels(int tw, int th, int[] palette)
    throws Exception {

    int ptr = 0;
    int end = ptr + tw * th;
    while (ptr < end) {
      int index = zrleInStream.readU8();
      int len = 1;
      if ((index & 128) != 0) {
        int b;
        do {
          b = zrleInStream.readU8();
          len += b;
        } while (b == 255);

        if (!(len <= end - ptr))
          throw new Exception("ZRLE decoder: assertion failed" +
                              " (len <= end - ptr)");
      }

      index &= 127;
      int pix = palette[index];

      if (bytesPixel == 1) {
        while (len-- > 0) zrleTilePixels8[ptr++] = (byte)pix;
      } else {
        while (len-- > 0) zrleTilePixels24[ptr++] = pix;
      }
    }
  }

  //
  // Copy pixels from zrleTilePixels8 or zrleTilePixels24, then update.
  //

  void handleUpdatedZrleTile(int x, int y, int w, int h) {
    Object src, dst;
    if (bytesPixel == 1) {
      src = zrleTilePixels8; dst = pixels8;
    } else {
      src = zrleTilePixels24; dst = pixels24;
    }
    int offsetSrc = 0;
    int offsetDst = (y * rfb.framebufferWidth + x);
    for (int j = 0; j < h; j++) {
      System.arraycopy(src, offsetSrc, dst, offsetDst, w);
      offsetSrc += w;
      offsetDst += rfb.framebufferWidth;
    }
    handleUpdatedPixels(x, y, w, h);
  }

  //
  // Handle a Zlib-encoded rectangle.
  //

  void handleZlibRect(int x, int y, int w, int h) throws Exception {

    int nBytes = rfb.readU32();

    if (zlibBuf == null || zlibBufLen < nBytes) {
      zlibBufLen = nBytes * 2;
      zlibBuf = new byte[zlibBufLen];
    }

    rfb.readFully(zlibBuf, 0, nBytes);

    if (rfb.rec != null && rfb.recordFromBeginning) {
      rfb.rec.writeIntBE(nBytes);
      rfb.rec.write(zlibBuf, 0, nBytes);
    }

    if (zlibInflater == null) {
      zlibInflater = new Inflater();
    }
    zlibInflater.setInput(zlibBuf, 0, nBytes);

    if (bytesPixel == 1) {
      for (int dy = y; dy < y + h; dy++) {
	zlibInflater.inflate(pixels8, dy * rfb.framebufferWidth + x, w);
	if (rfb.rec != null && !rfb.recordFromBeginning)
	  rfb.rec.write(pixels8, dy * rfb.framebufferWidth + x, w);
      }
    } else {
      byte[] buf = new byte[w * 4];
      int i, offset;
      for (int dy = y; dy < y + h; dy++) {
	zlibInflater.inflate(buf);
	offset = dy * rfb.framebufferWidth + x;
	for (i = 0; i < w; i++) {
	  pixels24[offset + i] =
	    (buf[i * 4 + 2] & 0xFF) << 16 |
	    (buf[i * 4 + 1] & 0xFF) << 8 |
	    (buf[i * 4] & 0xFF);
	}
	if (rfb.rec != null && !rfb.recordFromBeginning)
	  rfb.rec.write(buf);
      }
    }

    handleUpdatedPixels(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a Tight-encoded rectangle.
  //

  void handleTightRect(int x, int y, int w, int h) throws Exception {
    tightDecoder.handleRect(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Display newly updated area of pixels.
  //

  void handleUpdatedPixels(int x, int y, int w, int h) {

    // Draw updated pixels of the off-screen image.
    pixelsSource.newPixels(x, y, w, h);
    memGraphics.setClip(x, y, w, h);
    memGraphics.drawImage(rawPixelsImage, 0, 0, null);
    memGraphics.setClip(0, 0, rfb.framebufferWidth, rfb.framebufferHeight);
  }

  //
  // Tell JVM to repaint specified desktop area.
  //

  public void scheduleRepaint(int x, int y, int w, int h) {
    // Request repaint, deferred if necessary.
    if (rfb.framebufferWidth == scaledWidth) {
      repaint(viewer.deferScreenUpdates, x, y, w, h);
    } else {
      int sx = x * scalingFactor / 100;
      int sy = y * scalingFactor / 100;
      int sw = ((x + w) * scalingFactor + 49) / 100 - sx + 1;
      int sh = ((y + h) * scalingFactor + 49) / 100 - sy + 1;
      repaint(viewer.deferScreenUpdates, sx, sy, sw, sh);
    }
  }

  //
  // Handle events.
  //

  public void keyPressed(KeyEvent evt) {
    processLocalKeyEvent(evt);
  }
  public void keyReleased(KeyEvent evt) {
    processLocalKeyEvent(evt);
  }
  public void keyTyped(KeyEvent evt) {
    evt.consume();
  }

  public void mousePressed(MouseEvent evt) {
    processLocalMouseEvent(evt, false);
  }
  public void mouseReleased(MouseEvent evt) {
    processLocalMouseEvent(evt, false);
  }
  public void mouseMoved(MouseEvent evt) {
    processLocalMouseEvent(evt, true);
  }
  public void mouseDragged(MouseEvent evt) {
    processLocalMouseEvent(evt, true);
  }

  //
  // Ignored events.
  //

  public void mouseClicked(MouseEvent evt) {}
  public void mouseEntered(MouseEvent evt) {}
  public void mouseExited(MouseEvent evt) {}

  //
  // Actual event processing.
  //

  private void processLocalKeyEvent(KeyEvent evt) {
    if (viewer.rfb != null && rfb.inNormalProtocol) {
      if (!inputEnabled) {
	if ((evt.getKeyChar() == 'r' || evt.getKeyChar() == 'R') &&
	    evt.getID() == KeyEvent.KEY_PRESSED ) {
	  // Request screen update.
	  try {
	    rfb.writeFramebufferUpdateRequest(0, 0, rfb.framebufferWidth,
					      rfb.framebufferHeight, false);
	  } catch (IOException e) {
	    e.printStackTrace();
	  }
	}
      } else {
	// Input enabled.
	synchronized(rfb) {
	  try {
	    rfb.writeKeyEvent(evt);
	  } catch (Exception e) {
	    e.printStackTrace();
	  }
	  rfb.notify();
	}
      }
    }
    // Don't ever pass keyboard events to AWT for default processing.
    // Otherwise, pressing Tab would switch focus to ButtonPanel etc.
    evt.consume();
  }

  private void processLocalMouseEvent(MouseEvent evt, boolean moved) {
    if (viewer.rfb != null && rfb.inNormalProtocol) {
      if (!inSelectionMode) {
        if (inputEnabled) {
          sendMouseEvent(evt, moved);
        }
      } else {
        handleSelectionMouseEvent(evt);
      }
    }
  }

  private void sendMouseEvent(MouseEvent evt, boolean moved) {
    if (moved) {
      softCursorMove(evt.getX(), evt.getY());
    }
    if (rfb.framebufferWidth != scaledWidth) {
      int sx = (evt.getX() * 100 + scalingFactor/2) / scalingFactor;
      int sy = (evt.getY() * 100 + scalingFactor/2) / scalingFactor;
      evt.translatePoint(sx - evt.getX(), sy - evt.getY());
    }
    synchronized(rfb) {
      try {
        rfb.writePointerEvent(evt);
      } catch (Exception e) {
        e.printStackTrace();
      }
      rfb.notify();
    }
  }

  //
  // Reset update statistics.
  //

  void resetStats() {
    statStartTime = System.currentTimeMillis();
    statNumUpdates = 0;
    statNumTotalRects = 0;
    statNumPixelRects = 0;
    statNumRectsTight = 0;
    statNumRectsTightJPEG = 0;
    statNumRectsZRLE = 0;
    statNumRectsHextile = 0;
    statNumRectsRaw = 0;
    statNumRectsCopy = 0;
    statNumBytesEncoded = 0;
    statNumBytesDecoded = 0;
    if (tightDecoder != null)
      tightDecoder.setNumJPEGRects(0);
  }

  //////////////////////////////////////////////////////////////////
  //
  // Handle cursor shape updates (XCursor and RichCursor encodings).
  //

  boolean showSoftCursor = false;

  MemoryImageSource softCursorSource;
  Image softCursor;

  int cursorX = 0, cursorY = 0;
  int cursorWidth, cursorHeight;
  int origCursorWidth, origCursorHeight;
  int hotX, hotY;
  int origHotX, origHotY;

  //
  // Handle cursor shape update (XCursor and RichCursor encodings).
  //

  synchronized void
    handleCursorShapeUpdate(int encodingType,
			    int xhot, int yhot, int width, int height)
    throws IOException {

    softCursorFree();

    if (width * height == 0)
      return;

    // Ignore cursor shape data if requested by user.
    if (viewer.options.ignoreCursorUpdates) {
      int bytesPerRow = (width + 7) / 8;
      int bytesMaskData = bytesPerRow * height;

      if (encodingType == rfb.EncodingXCursor) {
	rfb.skipBytes(6 + bytesMaskData * 2);
      } else {
	// rfb.EncodingRichCursor
	rfb.skipBytes(width * height + bytesMaskData);
      }
      return;
    }

    // Decode cursor pixel data.
    softCursorSource = decodeCursorShape(encodingType, width, height);

    // Set original (non-scaled) cursor dimensions.
    origCursorWidth = width;
    origCursorHeight = height;
    origHotX = xhot;
    origHotY = yhot;

    // Create off-screen cursor image.
    createSoftCursor();

    // Show the cursor.
    showSoftCursor = true;
    repaint(viewer.deferCursorUpdates,
	    cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight);
  }

  //
  // decodeCursorShape(). Decode cursor pixel data and return
  // corresponding MemoryImageSource instance.
  //

  synchronized MemoryImageSource
    decodeCursorShape(int encodingType, int width, int height)
    throws IOException {

    int bytesPerRow = (width + 7) / 8;
    int bytesMaskData = bytesPerRow * height;

    int[] softCursorPixels = new int[width * height];

    if (encodingType == rfb.EncodingXCursor) {

      // Read foreground and background colors of the cursor.
      byte[] rgb = new byte[6];
      rfb.readFully(rgb);
      int[] colors = { (0xFF000000 | (rgb[3] & 0xFF) << 16 |
			(rgb[4] & 0xFF) << 8 | (rgb[5] & 0xFF)),
		       (0xFF000000 | (rgb[0] & 0xFF) << 16 |
			(rgb[1] & 0xFF) << 8 | (rgb[2] & 0xFF)) };

      // Read pixel and mask data.
      byte[] pixBuf = new byte[bytesMaskData];
      rfb.readFully(pixBuf);
      byte[] maskBuf = new byte[bytesMaskData];
      rfb.readFully(maskBuf);

      // Decode pixel data into softCursorPixels[].
      byte pixByte, maskByte;
      int x, y, n, result;
      int i = 0;
      for (y = 0; y < height; y++) {
	for (x = 0; x < width / 8; x++) {
	  pixByte = pixBuf[y * bytesPerRow + x];
	  maskByte = maskBuf[y * bytesPerRow + x];
	  for (n = 7; n >= 0; n--) {
	    if ((maskByte >> n & 1) != 0) {
	      result = colors[pixByte >> n & 1];
	    } else {
	      result = 0;	// Transparent pixel
	    }
	    softCursorPixels[i++] = result;
	  }
	}
	for (n = 7; n >= 8 - width % 8; n--) {
	  if ((maskBuf[y * bytesPerRow + x] >> n & 1) != 0) {
	    result = colors[pixBuf[y * bytesPerRow + x] >> n & 1];
	  } else {
	    result = 0;		// Transparent pixel
	  }
	  softCursorPixels[i++] = result;
	}
      }

    } else {
      // encodingType == rfb.EncodingRichCursor

      // Read pixel and mask data.
      byte[] pixBuf = new byte[width * height * bytesPixel];
      rfb.readFully(pixBuf);
      byte[] maskBuf = new byte[bytesMaskData];
      rfb.readFully(maskBuf);

      // Decode pixel data into softCursorPixels[].
      byte pixByte, maskByte;
      int x, y, n, result;
      int i = 0;
      for (y = 0; y < height; y++) {
	for (x = 0; x < width / 8; x++) {
	  maskByte = maskBuf[y * bytesPerRow + x];
	  for (n = 7; n >= 0; n--) {
	    if ((maskByte >> n & 1) != 0) {
	      if (bytesPixel == 1) {
		result = cm8.getRGB(pixBuf[i]);
	      } else {
		result = 0xFF000000 |
		  (pixBuf[i * 4 + 2] & 0xFF) << 16 |
		  (pixBuf[i * 4 + 1] & 0xFF) << 8 |
		  (pixBuf[i * 4] & 0xFF);
	      }
	    } else {
	      result = 0;	// Transparent pixel
	    }
	    softCursorPixels[i++] = result;
	  }
	}
	for (n = 7; n >= 8 - width % 8; n--) {
	  if ((maskBuf[y * bytesPerRow + x] >> n & 1) != 0) {
	    if (bytesPixel == 1) {
	      result = cm8.getRGB(pixBuf[i]);
	    } else {
	      result = 0xFF000000 |
		(pixBuf[i * 4 + 2] & 0xFF) << 16 |
		(pixBuf[i * 4 + 1] & 0xFF) << 8 |
		(pixBuf[i * 4] & 0xFF);
	    }
	  } else {
	    result = 0;		// Transparent pixel
	  }
	  softCursorPixels[i++] = result;
	}
      }

    }

    return new MemoryImageSource(width, height, softCursorPixels, 0, width);
  }

  //
  // createSoftCursor(). Assign softCursor new Image (scaled if necessary).
  // Uses softCursorSource as a source for new cursor image.
  //

  synchronized void
    createSoftCursor() {

    if (softCursorSource == null)
      return;

    int scaleCursor = viewer.options.scaleCursor;
    if (scaleCursor == 0 || !inputEnabled)
      scaleCursor = 100;

    // Save original cursor coordinates.
    int x = cursorX - hotX;
    int y = cursorY - hotY;
    int w = cursorWidth;
    int h = cursorHeight;

    cursorWidth = (origCursorWidth * scaleCursor + 50) / 100;
    cursorHeight = (origCursorHeight * scaleCursor + 50) / 100;
    hotX = (origHotX * scaleCursor + 50) / 100;
    hotY = (origHotY * scaleCursor + 50) / 100;
    softCursor = Toolkit.getDefaultToolkit().createImage(softCursorSource);

    if (scaleCursor != 100) {
      softCursor = softCursor.getScaledInstance(cursorWidth, cursorHeight,
						Image.SCALE_SMOOTH);
    }

    if (showSoftCursor) {
      // Compute screen area to update.
      x = Math.min(x, cursorX - hotX);
      y = Math.min(y, cursorY - hotY);
      w = Math.max(w, cursorWidth);
      h = Math.max(h, cursorHeight);

      repaint(viewer.deferCursorUpdates, x, y, w, h);
    }
  }

  //
  // softCursorMove(). Moves soft cursor into a particular location.
  //

  synchronized void softCursorMove(int x, int y) {
    int oldX = cursorX;
    int oldY = cursorY;
    cursorX = x;
    cursorY = y;
    if (showSoftCursor) {
      repaint(viewer.deferCursorUpdates,
	      oldX - hotX, oldY - hotY, cursorWidth, cursorHeight);
      repaint(viewer.deferCursorUpdates,
	      cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight);
    }
  }

  //
  // softCursorFree(). Remove soft cursor, dispose resources.
  //

  synchronized void softCursorFree() {
    if (showSoftCursor) {
      showSoftCursor = false;
      softCursor = null;
      softCursorSource = null;

      repaint(viewer.deferCursorUpdates,
	      cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight);
    }
  }

  //////////////////////////////////////////////////////////////////
  //
  // Support for selecting a rectangular video area.
  //

  /** This flag is false in normal operation, and true in the selection mode. */
  private boolean inSelectionMode;

  /** The point where the selection was started. */
  private Point selectionStart;

  /** The second point of the selection. */
  private Point selectionEnd;

  /**
   * We change cursor when enabling the selection mode. In this variable, we
   * save the original cursor so we can restore it on returning to the normal
   * mode.
   */
  private Cursor savedCursor;

  /**
   * Initialize selection-related varibles.
   */
  private synchronized void resetSelection() {
    inSelectionMode = false;
    selectionStart = new Point(0, 0);
    selectionEnd = new Point(0, 0);

    savedCursor = getCursor();
  }

  /**
   * Check current state of the selection mode.
   * @return true in the selection mode, false otherwise.
   */
  public boolean isInSelectionMode() {
    return inSelectionMode;
  }

  /**
   * Get current selection.
   * @param useScreenCoords use screen coordinates if true, or framebuffer
   * coordinates if false. This makes difference when scaling factor is not 100.
   * @return The selection as a {@link Rectangle}.
   */
  private synchronized Rectangle getSelection(boolean useScreenCoords) {
    int x0 = selectionStart.x;
    int x1 = selectionEnd.x;
    int y0 = selectionStart.y;
    int y1 = selectionEnd.y;
    // Make x and y point to the upper left corner of the selection.
    if (x1 < x0) {
      int t = x0; x0 = x1; x1 = t;
    }
    if (y1 < y0) {
      int t = y0; y0 = y1; y1 = t;
    }
    // Include the borders in the selection (unless it's empty).
    if (x0 != x1 && y0 != y1) {
      x1 += 1;
      y1 += 1;
    }
    // Translate from screen coordinates to framebuffer coordinates.
    if (rfb.framebufferWidth != scaledWidth) {
      x0 = (x0 * 100 + scalingFactor/2) / scalingFactor;
      y0 = (y0 * 100 + scalingFactor/2) / scalingFactor;
      x1 = (x1 * 100 + scalingFactor/2) / scalingFactor;
      y1 = (y1 * 100 + scalingFactor/2) / scalingFactor;
    }
    // Clip the selection to framebuffer.
    if (x0 < 0)
      x0 = 0;
    if (y0 < 0)
      y0 = 0;
    if (x1 > rfb.framebufferWidth)
      x1 = rfb.framebufferWidth;
    if (y1 > rfb.framebufferHeight)
      y1 = rfb.framebufferHeight;
    // Make width a multiple of 16.
    int widthBlocks = (x1 - x0 + 8) / 16;
    if (selectionStart.x <= selectionEnd.x) {
      x1 = x0 + widthBlocks * 16;
      if (x1 > rfb.framebufferWidth) {
        x1 -= 16;
      }
    } else {
      x0 = x1 - widthBlocks * 16;
      if (x0 < 0) {
        x0 += 16;
      }
    }
    // Make height a multiple of 8.
    int heightBlocks = (y1 - y0 + 4) / 8;
    if (selectionStart.y <= selectionEnd.y) {
      y1 = y0 + heightBlocks * 8;
      if (y1 > rfb.framebufferHeight) {
        y1 -= 8;
      }
    } else {
      y0 = y1 - heightBlocks * 8;
      if (y0 < 0) {
        y0 += 8;
      }
    }
    // Translate the selection back to screen coordinates if requested.
    if (useScreenCoords && rfb.framebufferWidth != scaledWidth) {
      x0 = (x0 * scalingFactor + 50) / 100;
      y0 = (y0 * scalingFactor + 50) / 100;
      x1 = (x1 * scalingFactor + 50) / 100;
      y1 = (y1 * scalingFactor + 50) / 100;
    }
    // Construct and return the result.
    return new Rectangle(x0, y0, x1 - x0, y1 - y0);
  }

  /**
   * Enable or disable the selection mode.
   * @param enable enables the selection mode if true, disables if fasle.
   */
  public synchronized void enableSelection(boolean enable) {
    if (enable && !inSelectionMode) {
      // Enter the selection mode.
      inSelectionMode = true;
      savedCursor = getCursor();
      setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
      repaint();
    } else if (!enable && inSelectionMode) {
      // Leave the selection mode.
      inSelectionMode = false;
      setCursor(savedCursor);
      repaint();
    }
  }

  /**
   * Process mouse events in the selection mode.
   *
   * @param evt mouse event that was originally passed to
   *   {@link MouseListener} or {@link MouseMotionListener}.
   */
  private synchronized void handleSelectionMouseEvent(MouseEvent evt) {
    int id = evt.getID();
    boolean button1 = (evt.getModifiers() & InputEvent.BUTTON1_MASK) != 0;

    if (id == MouseEvent.MOUSE_PRESSED && button1) {
      selectionStart = selectionEnd = evt.getPoint();
      repaint();
    }
    if (id == MouseEvent.MOUSE_DRAGGED && button1) {
      selectionEnd = evt.getPoint();
      repaint();
    }
    if (id == MouseEvent.MOUSE_RELEASED && button1) {
      try {
        rfb.trySendVideoSelection(getSelection(false));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  //
  // Override RecordInterface methods
  //

  public boolean isRecordFromBeginning() {
    return rfb.recordFromBeginning;
  }

  public boolean canWrite() {
    // We can record if rec is not null
    return rfb.rec != null;
  }

  public void write(byte b[]) throws IOException {
    rfb.rec.write(b);
  }

  public void write(byte b[], int off, int len) throws IOException {
    rfb.rec.write(b, off, len);
  }

  public void writeByte(byte b) throws IOException {
    rfb.rec.writeByte(b);
  }

  public void writeByte(int i) throws IOException {
    rfb.rec.writeByte(i);
  }

  public void writeIntBE(int v) throws IOException {
    rfb.rec.writeIntBE(v);
  }

  public void recordCompactLen(int len) throws IOException {
    rfb.recordCompactLen(len);
  }

  public void recordCompressedData(byte[] data) throws IOException {
    rfb.recordCompressedData(data);
  }
}
