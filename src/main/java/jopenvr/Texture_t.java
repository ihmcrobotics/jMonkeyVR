package jopenvr;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
/**
 * This file was autogenerated by <a href="http://jnaerator.googlecode.com/">JNAerator</a>,<br>
 * a tool written by <a href="http://ochafik.com/">Olivier Chafik</a> that <a href="http://code.google.com/p/jnaerator/wiki/CreditsAndLicense">uses a few opensource projects.</a>.<br>
 * For help, please visit <a href="http://nativelibs4java.googlecode.com/">NativeLibs4Java</a> , <a href="http://rococoa.dev.java.net/">Rococoa</a>, or <a href="http://jna.dev.java.net/">JNA</a>.
 */
public class Texture_t extends Structure {
	/**
	 * void *<br>
	 * C type : void*
	 */
	public int handle;
	/**
	 * @see EGraphicsAPIConvention<br>
	 * C type : EGraphicsAPIConvention
	 */
	public int eType;
	/**
	 * @see EColorSpace<br>
	 * C type : EColorSpace
	 */
	public int eColorSpace;
	public Texture_t() {
		super();
	}
	protected List<? > getFieldOrder() {
		return Arrays.asList("handle", "eType", "eColorSpace");
	}
	/**
	 * @param handle void *<br>
	 * C type : void*<br>
	 * @param eType @see EGraphicsAPIConvention<br>
	 * C type : EGraphicsAPIConvention<br>
	 * @param eColorSpace @see EColorSpace<br>
	 * C type : EColorSpace
	 */
	public Texture_t(int handle, int eType, int eColorSpace) {
		super();
		this.handle = handle;
		this.eType = eType;
		this.eColorSpace = eColorSpace;
	}
	public Texture_t(Pointer peer) {
		super(peer);
	}
	public static class ByReference extends Texture_t implements Structure.ByReference {
		
	};
	public static class ByValue extends Texture_t implements Structure.ByValue {
		
	};
}