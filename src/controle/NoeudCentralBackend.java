package controle;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import modele.AbriException;
import modele.AnnuaireNoeudCentral;
import modele.Message;
import modele.MessageType;
import modele.NoeudCentral;
import modele.NoeudCentralException;

/**
 *
 * @author Gwenole Lecorve
 * @author David Guennec
 */
public class NoeudCentralBackend extends UnicastRemoteObject implements NoeudCentralRemoteInterface
{
	private static final long serialVersionUID = -5192481389891341422L;

	protected String urlNoeud;
	protected NoeudCentral noeudCentral;
	protected SectionCritiqueNoeudControleur sectionCritiqueControleur; // Controleur gérant la section critique. Mettre l'algorithme d'exclusion mutuelle ici.
	protected AnnuaireNoeudCentral abris; // Le noeud connait tous les abris du réseau.

	public NoeudCentralBackend(final String url) throws RemoteException, MalformedURLException
	{
		this.urlNoeud = url;
		noeudCentral = new NoeudCentral();
		sectionCritiqueControleur = new SectionCritiqueNoeudControleur(this.urlNoeud);
		abris = new AnnuaireNoeudCentral();
		Naming.rebind(this.urlNoeud, this);
	}

	@Override
	public void finalize() throws RemoteException, NotBoundException, MalformedURLException, Throwable
	{
		try {
			Naming.unbind(urlNoeud);
		} finally {
			super.finalize();
		}
	}

	@Override
	public void deconnecterAbri(final String url) throws AbriException, NoeudCentralException, RemoteException
	{
		// Faire un broadcast pour prévenir les autres qu'il y a un abri en moins
		for ( Entry<String, AbriRemoteInterface> autreAbri : abris.getAbrisDistants().entrySet() ) {
			if ( !autreAbri.getKey().equals(url) ) {
				// FIXME
				autreAbri.getValue().recevoirMessage(new Message(url, Arrays.asList(autreAbri.getKey()), MessageType.SIGNALEMENT_DECONNECTION));
				//	autreAbri.getValue().supprimerAbri(autreAbri.getKey());
			}
		}
		// Mettre à jour son annuaire
		abris.retirerAbriDistant(url);
	}

	public NoeudCentral getNoeudCentral()
	{
		return noeudCentral;
	}

	public AnnuaireNoeudCentral getAnnuaire()
	{
		return abris;
	}

	@Override
	public void modifierAiguillage(final String depuisUrl, final List<String> versUrl) throws RemoteException, NoeudCentralException
	{
		System.out.println("JE SUIS LE NOEUD CENTRAL, JE ME RECONFIGURE DEPUIS " + depuisUrl + " VERS " + versUrl);
		noeudCentral.reconfigurerAiguillage(depuisUrl, versUrl);
	}

	@Override
	// On doit avoir la section critique quand on est dans cette méthode. On ne doit pas quitter la SC dans cette méthode.
	public void transmettreMessage(final Message message) throws RemoteException, AbriException, NoeudCentralException
	{
		modifierAiguillage(message.getUrlEmetteur(), message.getUrlDestinataire());
		try {
			noeudCentral.demarrerTransmission();
			// Tous les destinataires recoivent
			for ( String abri : noeudCentral.getVersUrl() ) {
				abris.chercherUrl(abri).recevoirMessage(message);
				System.out.println("ABRI '" + abri + "' VIENS DE RECEVOIR LE MESSAGE '" + message + "'");
			}
		} finally {
			noeudCentral.stopperTransmission();
		}
	}

	@Override
	// le noeud central recoit un message signalant qu'un nouvel abri a rejoint le réseau
	public void connexionAbri(final String urlAbriDistant, final String groupeAbri) throws RemoteException, NotBoundException, MalformedURLException, AbriException, NoeudCentralException
	{
		System.out.println("JE SUIS LE NOEUD, ABRI " + urlAbriDistant + " VIENS DE S'AJOUTER, JE DOIS PREVENIR " + abris.getAbrisDistants().size() + " AUTRES ABRIS.");
		// On mémorise dans l'annuaire du noeud central
		AbriRemoteInterface abriDistant = (AbriRemoteInterface) Naming.lookup(urlAbriDistant);
		abris.ajouterAbriDistant(urlAbriDistant, abriDistant);

		// On envoie un broadcast à tout les abris éxistants pour signaler la présence du nouveau.
		for ( Entry<String, AbriRemoteInterface> autreAbri : abris.getAbrisDistants().entrySet() ) {
			if ( !autreAbri.getKey().equals(urlAbriDistant) ) {
				System.out.println(autreAbri.getKey() + " RECOIS " + urlAbriDistant);
				//autreAbri.getValue().enregistrerAbri(urlAbriDistant, groupeAbri);
				autreAbri.getValue().recevoirMessage(new Message(urlAbriDistant, Arrays.asList(autreAbri.getKey()), groupeAbri, MessageType.SIGNALEMENT_CONNECTION));
			}
		}
	}

	@Override
	public synchronized void demanderSectionCritique(final String url) throws RemoteException, AbriException, NoeudCentralException
	{
		System.out.println("JE SUIS " + url + " ET JE VIENS D'ARRIVER AU NOEUD POUR UNE DEMANDE DE SC");
		boolean available = sectionCritiqueControleur.demanderSectionCritique(url);
		if ( available ) { // SC dispo immédiatement
			sectionCritiqueControleur.setUrlEnSC(url);
			//abris.chercherUrl(url).recevoirSC();
			abris.chercherUrl(url).recevoirMessage(new Message(this.urlNoeud, Arrays.asList(url), MessageType.SIGNALEMENT_AUTORISATION_SC));
		}
	}

	@Override
	public void quitterSectionCritique(final String url) throws RemoteException, AbriException, NoeudCentralException
	{
		System.out.println("JE SUIS " + url + " ET JE VIENS D'ARRIVER AU NOEUD POUR QUITTER LA SC");
		// On enregistre le fait que l'abris actuel libère la SC
		String prochain = null;
		try {
			prochain = sectionCritiqueControleur.quitterSectionCritique(url);
		} catch ( IllegalAccessException e ) {
			e.printStackTrace();
			System.exit(-1); // FIXME
		}

		// Un abri était en attente pour avoir la SC, on la lui donne
		if ( prochain != null ) {
			System.out.println("JE SUIS LE NOEUD, UN ABRI A QUITTE LA SC ET JE LA DONNE MAINTENANT A " + prochain);
			sectionCritiqueControleur.setUrlEnSC(prochain);
			//	abris.chercherUrl(prochain).recevoirSC();
			abris.chercherUrl(prochain).recevoirMessage(new Message(urlNoeud, Arrays.asList(prochain), MessageType.SIGNALEMENT_AUTORISATION_SC));
		} else {
			System.out.println("JE SUIS LE NOEUD, UN ABRI A QUITTE LA SC ET PERSONNE EN ATTENTE");
		}
	}

}
