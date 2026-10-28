package re.lilith.aurum.shaderpack.texture;

import re.lilith.aurum.gl.texture.InternalTextureFormat;
import re.lilith.aurum.gl.texture.PixelFormat;
import re.lilith.aurum.gl.texture.PixelType;
import re.lilith.aurum.gl.texture.TextureType;

public abstract class CustomTextureData {
    private CustomTextureData() {

    }

    public static final class PngData extends CustomTextureData {
        private final TextureFilteringData filteringData;
        private final byte[] content;

        public PngData(TextureFilteringData filteringData, byte[] content) {
            this.filteringData = filteringData;
            this.content = content;
        }

        public TextureFilteringData getFilteringData() {
            return filteringData;
        }

        public byte[] getContent() {
            return content;
        }
    }

    public static final class LightmapMarker extends CustomTextureData {
        @Override
        public boolean equals(Object obj) {
            return obj.getClass() == this.getClass();
        }

        @Override
        public int hashCode() {
            return 33;
        }
    }

    public static final class ResourceData extends CustomTextureData {
        private final String namespace;
        private final String location;

        public ResourceData(String namespace, String location) {
            this.namespace = namespace;
            this.location = location;
        }

        /**
         * @return The namespace of the texture. The caller is responsible for checking whether this is actually
         * a valid namespace.
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * @return The path / location of the texture. The caller is responsible for checking whether this is actually
         * a valid path.
         */
        public String getLocation() {
            return location;
        }
    }

    public static final class RawData extends CustomTextureData {
        private final byte[] content;
        private final TextureType type;
        private final InternalTextureFormat internalFormat;
        private final PixelFormat pixelFormat;
        private final PixelType pixelType;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final TextureFilteringData filteringData;

        public RawData(byte[] content, TextureType type, InternalTextureFormat internalFormat, PixelFormat pixelFormat,
                       PixelType pixelType, int sizeX, int sizeY, int sizeZ, TextureFilteringData filteringData) {
            this.content = content;
            this.type = type;
            this.internalFormat = internalFormat;
            this.pixelFormat = pixelFormat;
            this.pixelType = pixelType;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.filteringData = filteringData;
        }

        public byte[] getContent() {
            return content;
        }

        public TextureType getType() {
            return type;
        }

        public InternalTextureFormat getInternalFormat() {
            return internalFormat;
        }

        public PixelFormat getPixelFormat() {
            return pixelFormat;
        }

        public PixelType getPixelType() {
            return pixelType;
        }

        public int getSizeX() {
            return sizeX;
        }

        public int getSizeY() {
            return sizeY;
        }

        public int getSizeZ() {
            return sizeZ;
        }

        public TextureFilteringData getFilteringData() {
            return filteringData;
        }
    }
}
