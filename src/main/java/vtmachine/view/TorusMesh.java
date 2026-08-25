package vtmachine.view;

import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

/** Lightweight torus used instead of adding a third-party geometry dependency. */
final class TorusMesh extends MeshView {
    TorusMesh(float majorRadius, float tubeRadius, int majorSegments, int tubeSegments) {
        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_TEXCOORD);
        float[] points = new float[majorSegments * tubeSegments * 3];
        int point = 0;
        for (int i = 0; i < majorSegments; i++) {
            double u = Math.PI * 2 * i / majorSegments;
            for (int j = 0; j < tubeSegments; j++) {
                double v = Math.PI * 2 * j / tubeSegments;
                double radial = majorRadius + tubeRadius * Math.cos(v);
                points[point++] = (float) (radial * Math.cos(u));
                points[point++] = (float) (tubeRadius * Math.sin(v));
                points[point++] = (float) (radial * Math.sin(u));
            }
        }
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);

        int[] faces = new int[majorSegments * tubeSegments * 12];
        int face = 0;
        for (int i = 0; i < majorSegments; i++) {
            int ni = (i + 1) % majorSegments;
            for (int j = 0; j < tubeSegments; j++) {
                int nj = (j + 1) % tubeSegments;
                int p00 = i * tubeSegments + j;
                int p10 = ni * tubeSegments + j;
                int p11 = ni * tubeSegments + nj;
                int p01 = i * tubeSegments + nj;
                faces[face++] = p00; faces[face++] = 0;
                faces[face++] = p10; faces[face++] = 0;
                faces[face++] = p11; faces[face++] = 0;
                faces[face++] = p00; faces[face++] = 0;
                faces[face++] = p11; faces[face++] = 0;
                faces[face++] = p01; faces[face++] = 0;
            }
        }
        mesh.getFaces().setAll(faces);
        int[] smoothing = new int[majorSegments * tubeSegments * 2];
        java.util.Arrays.fill(smoothing, 1);
        mesh.getFaceSmoothingGroups().setAll(smoothing);
        setMesh(mesh);
        setCullFace(CullFace.NONE);
    }
}
