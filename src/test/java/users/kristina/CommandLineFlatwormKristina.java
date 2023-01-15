package users.kristina;

import mpicbg.spim.data.SpimDataException;
import org.embl.mobie.cmd.MoBIECmd;

class CommandLineFlatwormKristina
{
	public static final String ROOT = "/Users/tischer/Desktop/Kristina/output/";
	public static final String DATASET = "large-worm";

	public static void main( String[] args ) throws SpimDataException
	{
		final MoBIECmd commandLineInterface = new MoBIECmd();
		commandLineInterface.run(
				new String[]{ ROOT + DATASET + "-pro.tif" },
				new String[]{ ROOT + DATASET + "-seg.tif" },
				new String[]{ ROOT + DATASET + ".csv" }
				);
	}
}