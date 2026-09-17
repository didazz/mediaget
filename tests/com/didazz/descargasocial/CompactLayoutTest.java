// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
public final class CompactLayoutTest {
    public static void main(String[] args) {
        int scenarios = 0;
        // Available workspace sizes after bars/tabs: phone, keyboard, landscape, tablet, enlarged text.
        int[][] sizes = {{296,450},{336,570},{387,630},{400,150},{640,230},{776,650},{976,1050},{200,20},{320,0}};
        for (int[] size : sizes) {
            for (int font : new int[]{100,130,200}) {
                for (boolean content : new boolean[]{true,false}) {
                    int w = size[0], h = size[1];
                    boolean wide = w >= 600 && w > h * 1.2;
                    CompactLayout p = new CompactLayout(w,h,130*font/100,48*font/100,48*font/100,8,wide,content);
                    CompactLayout.Box[] boxes = {p.editor,p.status,p.content,p.action};
                    for (CompactLayout.Box b : boxes) {
                        if (b.x < 0 || b.y < 0 || b.width < 0 || b.height < 0
                                || b.x+b.width > w || b.y+b.height > h) {
                            throw new AssertionError("Outside window " + w + "x" + h);
                        }
                    }
                    for (int i=0;i<boxes.length;i++) for(int j=i+1;j<boxes.length;j++) {
                        CompactLayout.Box a=boxes[i], b=boxes[j];
                        if(a.width>0 && a.height>0 && b.width>0 && b.height>0
                                && a.x < b.x+b.width && b.x < a.x+a.width
                                && a.y < b.y+b.height && b.y < a.y+a.height) {
                            throw new AssertionError("Overlapping controls");
                        }
                    }
                    if (h >= 96 && p.action.height < 48) { throw new AssertionError("Small touch target"); }
                    if (h >= 450 && content && p.content.height < 80) { throw new AssertionError("No preview space"); }
                    scenarios++;
                }
            }
        }
        System.out.println("CompactLayoutTest: " + scenarios + " escenarios de tamaño y texto");
    }
}
