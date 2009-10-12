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

    public static void main(String args[]) {
         new DrawTool();
    }

    DrawTool() {
        setTitle("DrawTool Ver 0.0000009");
        addMouseMotionListener(mouseMotionListener);
        setSize(400, 400);
        setVisible(true);
    }

    public void doLayout() {
        Dimension d = getSize();

        if (d.width != 0 && d.height != 0) {
            Image img = offs;
            this.offs = createImage(d.width, d.height);
            this.offg = this.offs.getGraphics();

            // �O��̐F�̘g������
            this.offg.setColor(Color.white);
            this.offg.fillRect(0, 0, d.width, d.height);

            // �������A�ł�
            // �O��܂łɕ`�悳�ꂽ�C���[�W��V�����C���[�W
            // �ɃR�s�[����B
            if (img != null) {
                offg.drawImage(img, 0, 0, null);
            }
        }
    }

    public void update(Graphics g) {
        redraw(g);
    }

    public void paint(Graphics g) {
        redraw(g);
    }

    public void redraw(Graphics g) {
        if (this.offg != null) {
            this.offg.setColor(Color.red);
            g.drawImage(this.offs, 0, 0, null);
        }
    }

    MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
        // �}�E�X�Ńh���b�O���ꂽ�Ƃ���ɓ_��ł�
        public void mouseDragged(MouseEvent event) {
            offg.fillOval(event.getX(), event.getY(), 5, 5);
            repaint();
        }
    };
}
