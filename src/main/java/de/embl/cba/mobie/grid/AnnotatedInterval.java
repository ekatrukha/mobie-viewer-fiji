package de.embl.cba.mobie.grid;

import net.imglib2.RealInterval;

public interface AnnotatedInterval
{
	RealInterval getInterval();
	String getName();
}
