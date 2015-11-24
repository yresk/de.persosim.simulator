package de.persosim.simulator.perso;

import org.junit.Before;

import de.persosim.simulator.exception.AccessDeniedException;

public class Profile05Test extends ArtifactPersonalizationTest {

	Personalization perso;
	
	@Before
	public void setUp() throws Exception {
		perso = null;
	}

	@Override
	public Personalization getPerso() throws AccessDeniedException {
		
		if (perso == null) {
			perso = new Profile05();
		}
			
		return perso;
	}

}
