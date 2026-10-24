import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;


public class DrawTool extends Frame {
    Image img;

    Image offs = null;

    Graphics offg = null;

    static void main(String[] args) {
         new DrawTool();
    }

    DrawTool() {
        setTitle("DrawTool Ver 0.0000009");
        addMouseMotionListener(mouseMotionListener);
        setSize(400, 400);
        setVisible(true);
    }

    @Override
    public void doLayout() {
        Dimension d = getSize();

        if (d.width != 0 && d.height != 0) {
            Image img = offs;
            this.offs = createImage(d.width, d.height);
            this.offg = this.offs.getGraphics();

            // Remove the previous colored border.
            this.offg.setColor(Color.white);
            this.offg.fillRect(0, 0, d.width, d.height);

            // This is the end of the month.
            // The image drawn in the previous session is copied to the new image.
            if (img != null) {
                offg.drawImage(img, 0, 0, null);
            }
        }
    }

    @Override
    public void update(Graphics g) {
        redraw(g);
    }

    @Override
    public void paint(Graphics g) {
        redraw(g);
    }

    public void redraw(Graphics g) {
        if (this.offg != null) {
            this.offg.setColor(Color.red);
            g.drawImage(this.offs, 0, 0, null);
        }
    }

    final MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
        // Place a point where you drag with the mouse.
        @Override
        public void mouseDragged(MouseEvent event) {
            offg.fillOval(event.getX(), event.getY(), 5, 5);
            repaint();
        }
    };
}
