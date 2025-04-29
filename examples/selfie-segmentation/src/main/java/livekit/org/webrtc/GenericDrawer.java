package livekit.org.webrtc;

public class GenericDrawer extends GlGenericDrawer {
    public GenericDrawer(String genericFragmentSource, ShaderCallbacks shaderCallbacks) {
        super(genericFragmentSource, shaderCallbacks);
    }

    static final ShaderCallbacks EMPTY_CALLBACKS = new ShaderCallbacks() {

        @Override
        public void onNewShader(GlShader glShader) {

        }

        @Override
        public void onPrepareShader(GlShader glShader, float[] floats, int i, int i1, int i2, int i3) {
        }
    };

    public interface ShaderCallbacks extends GlGenericDrawer.ShaderCallbacks {
    }
}
