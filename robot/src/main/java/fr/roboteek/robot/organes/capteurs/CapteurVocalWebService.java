package fr.roboteek.robot.organes.capteurs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import com.google.common.primitives.Bytes;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.SilenceDetector;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm;
import be.tarsos.dsp.writer.WaveHeader;
import fr.roboteek.robot.Constantes;
import fr.roboteek.robot.organes.AbstractOrgane;
import fr.roboteek.robot.organes.actionneurs.OrganeParoleEspeak;
import fr.roboteek.robot.organes.actionneurs.OrganeParoleGoogle;
import fr.roboteek.robot.server.AudioWebSocket;
import fr.roboteek.robot.systemenerveux.event.ParoleEvent;
import fr.roboteek.robot.systemenerveux.event.ReconnaissanceVocaleEvent;
import fr.roboteek.robot.systemenerveux.event.RobotEventBus;
import fr.roboteek.robot.util.reconnaissance.vocale.SpeechRecognizer;
import fr.roboteek.robot.util.reconnaissance.vocale.bing.BingSpeechRecognizerRest;

/**
 * Capteur vocal.
 * @author Nicolas
 *
 */
public class CapteurVocalWebService extends AbstractOrgane {

	/** Longueur de l'entête d'un fichier WAV. */
	private static final int WAV_FILE_HEADER_LENGTH=44;

	/** Fréquence d'échantillonage. */
	private static final float sampleRate = 44100;

	/** Taille du buffer. */
	private static final int bufferSize = 1024 * 4;

	/** ?. */
	private static final int overlap = 0 ;

	/** Moteur de reconnaissance vocale. */
	private SpeechRecognizer recognizer;

	/** Format audio. */
	private AudioFormat format;

	/** Timestamp précédent (permet de connaître le temps depuis le dernier bloc "parlé"). */
	private double timestampDernierBlocParle = 0;

	/** Chemin du fichier WAV. */
	private String cheminFichierWav;

	/** Buffer permettant de stocker le signal audio précédent. */
	private byte[] bufferNMoins1;

	/** Buffer permettant de stocker le signal audio précédent. */
	private byte[] bufferNMoins2;

	/** Buffer permettant de stocker le signal audio précédent. */
	private byte[] bufferNMoins3;
	
	/** Buffer permettant de stocker le signal audio précédent. */
	private byte[] bufferNMoins4;
	
	/** Buffer permettant de stocker le signal audio précédent. */
	private byte[] bufferNMoins5;

	/** Buffer permettant de stocker une phase de reconnaissance. */
	private byte[] contenuParle;
	
	private RobotEventBus robotEventBus;


	public CapteurVocalWebService(SpeechRecognizer recognizer) {
		super();

		this.recognizer= recognizer;
		robotEventBus = RobotEventBus.getInstance();
	}

	@Override
	public void initialiser() {

		try {

			final Path dossierReconnaissanceVocaleGoogle = Paths.get(Constantes.DOSSIER_RECONNAISSANCE_VOCALE);
			if (!Files.exists(dossierReconnaissanceVocaleGoogle)) {
				// Création du dossier
				Files.createDirectories(dossierReconnaissanceVocaleGoogle);
			}
			cheminFichierWav = Constantes.DOSSIER_RECONNAISSANCE_VOCALE + File.separator + "reconnaissance.wav";

			// Définition du format audio d'acquisition
			format = new AudioFormat(sampleRate, 16, 1, true, false);

			// Récupération du flux du micro au format souhaité
			final DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, format);
			TargetDataLine line = (TargetDataLine) AudioSystem.getLine(dataLineInfo);
			final AudioInputStream stream = new AudioInputStream(line);
			final JVMAudioInputStream audioStream = new JVMAudioInputStream(stream);
			final AudioDispatcher dispatcher = new AudioDispatcher(audioStream, bufferSize, overlap);

			// Ouverture du flux et démarrage de l'acquisition
			line.open(format, bufferSize);
			line.start();

			// Initialisation des tableaux d'octets contenant les différents blocs audio
			contenuParle = new byte[0];
			bufferNMoins1 = new byte[0];
			bufferNMoins2 = new byte[0];
			bufferNMoins3 = new byte[0];
			bufferNMoins4 = new byte[0];
			bufferNMoins5 = new byte[0];

			// Création d'un processeur détectant les silences dans le flux
			final SilenceDetector silenceDetector = new SilenceDetector(SilenceDetector.DEFAULT_SILENCE_THRESHOLD,false);

			// Création d'un processeur permettant de déterminer la hauteur d'un bloc audio (permet de récupérer la fréquence)
			AudioProcessor p = new PitchProcessor(PitchEstimationAlgorithm.YIN, sampleRate, bufferSize, new PitchDetectionHandler() {

				public synchronized void handlePitch(PitchDetectionResult result,AudioEvent e) {

					// Flag permettant de savoir si le flux en cours de traitement est un flux "parlé"
					boolean isBlocParle = false;

					// Récupération du timestamp du bloc audio
					double timestampBlocEnCours = e.getTimeStamp();

					// On teste si le bloc en cours contient de la voix (bruit à une certaine fréquence)

					// Si le bloc ne correspond pas à un silence (volume au delà d'un certain seuil), on traite ce bloc
					if (silenceDetector.currentSPL() > SilenceDetector.DEFAULT_SILENCE_THRESHOLD) {

						// Récupération de la fréquence du bloc
						final float pitchInHz = result.getPitch();

						// Si le bloc est compris dans une certaine plage de fréquences : bloc contenant de la voix (parlé)
						if (pitchInHz > 0) {
							isBlocParle = true;
						} else {
							isBlocParle = false;
						}
					} else {
						isBlocParle = false;
					}

					if (isBlocParle || timestampBlocEnCours - timestampDernierBlocParle < 0.6) {
						// Si ça parle, ou petit silence
						// On concatène le bloc audio en cours au contenu général
						contenuParle = Bytes.concat(contenuParle, e.getByteBuffer());
					} else {
						// Ca ne parle pas et grand silence

						// On ne lance la reconnaissance que s'il y a du contenu
						if (contenuParle.length > 0) {
							lancerReconnaissance();

							// Réinitialisation des blocs
							contenuParle = new byte[0];
							bufferNMoins1 = new byte[0];
							bufferNMoins2 = new byte[0];
							bufferNMoins3 = new byte[0];
							bufferNMoins4 = new byte[0];
							bufferNMoins5 = new byte[0];
						} else {
							// Silence et pas de contenu : on ne fait rien
						}
					}

					// Mise à jour du timestamp précédent par celui du bloc audio en cours si c'est un bloc "parlé"
					if (isBlocParle) {
						timestampDernierBlocParle = timestampBlocEnCours;
					}

					// Si aucun bloc "parlé" depuis la dernière reconnaissance : échange des blocs précédant un éventuel bloc "parlé"
					if (contenuParle.length == 0) {
						bufferNMoins5 = Bytes.concat(bufferNMoins4);
						bufferNMoins4 = Bytes.concat(bufferNMoins3);
						bufferNMoins3 = Bytes.concat(bufferNMoins2);
						bufferNMoins2 = Bytes.concat(bufferNMoins1);
						bufferNMoins1 = Bytes.concat(e.getByteBuffer());
					}

					// Broadcast live du fichier WAV (entête + contenu)
					AudioWebSocket.broadcastAudio(creerFichierWav(e.getByteBuffer()));
				}

			});

			// Assemblage des différents processeurs dans le dispatcher
			dispatcher.addAudioProcessor(silenceDetector);
			dispatcher.addAudioProcessor(p);

			// Lancement du dispatcher dans un thread
			new Thread(dispatcher,"Audio Dispatcher").start();

		} catch (LineUnavailableException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	@Override
	public void arreter() {
		// TODO Auto-generated method stub

	}

	public synchronized void lancerReconnaissance() {

		try {
			// On ne lance la reconnaissance que s'il y a du contenu
			if (contenuParle.length > 0) {
				System.out.println("Lancement de la reconnaissance");

				// Concaténation du contenu parlé avec le contenu précédent
				contenuParle = Bytes.concat(bufferNMoins5, bufferNMoins4, bufferNMoins3, bufferNMoins2, bufferNMoins1, contenuParle);
				
				// Création du fichier Wav
				final String cheminFichierWavTemp = cheminFichierWav.replace(".wav", "") + System.currentTimeMillis() + ".wav";
				final RandomAccessFile fichierWavRandom = new RandomAccessFile(cheminFichierWavTemp, "rw");
				fichierWavRandom.seek(0);
				// Création du fichier WAV (entête + contenu)
				fichierWavRandom.write(creerFichierWav(contenuParle));
				fichierWavRandom.close();

				// Appel du moteur de reconnaissance
				long debut = System.currentTimeMillis();
				final String resultat = recognizer.reconnaitre(cheminFichierWavTemp);
				long fin = System.currentTimeMillis();
				System.out.println("Temps reconnaissance : " + (fin - debut));

				if (resultat != null && !resultat.trim().equals("")) {
					// Envoi de l'évènement de reconnaissance
					final ReconnaissanceVocaleEvent event = new ReconnaissanceVocaleEvent();
					event.setTexteReconnu(resultat);
					System.out.println("Résultat = " + resultat);
					// Suppression du fichier
//					fichierWav.delete();
					// Lancement de l'évènement de reconnaissance vocale
					RobotEventBus.getInstance().publishAsync(event);
				}

			}

		} catch (IOException e){
			e.printStackTrace();
		}
	}
	
	/**
	 * Crée l'entête WAV au contenu audio 
	 * @param contenuAudio le contenu audio
	 * @return le fichier WAV (entête + contenu) sous forme de tableau d'octets
	 */
	private byte[] creerFichierWav(byte[] contenuAudio) {
		// Création du header WAV
		WaveHeader waveHeader=new WaveHeader(WaveHeader.FORMAT_PCM,
				(short)format.getChannels(),
				(int)format.getSampleRate(),(short)16,contenuAudio.length);//16 is for pcm, Read WaveHeader class for more details
		ByteArrayOutputStream header=new ByteArrayOutputStream();
		try {
			waveHeader.write(header);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		return Bytes.concat(header.toByteArray(), contenuAudio);
	}

	public static void main(String[] args) {
		OrganeParoleEspeak organeParole = new OrganeParoleEspeak();
		RobotEventBus.getInstance().subscribe(organeParole);
		final CapteurVocalWebService capteurVocal = new CapteurVocalWebService(BingSpeechRecognizerRest.getInstance());
		capteurVocal.initialiser();
		RobotEventBus.getInstance().subscribe(capteurVocal);

	}

//	private float getDuration(File fichierSon) {
//		float durationInSeconds = 0f;
//		AudioInputStream audioInputStream;
//		try {
//			audioInputStream = AudioSystem.getAudioInputStream(fichierSon);
//			AudioFormat format = audioInputStream.getFormat();
//			long audioFileLength = fichierSon.length();
//			int frameSize = format.getFrameSize();
//			float frameRate = format.getFrameRate();
//			durationInSeconds = (audioFileLength / (frameSize * frameRate));
//		} catch (UnsupportedAudioFileException | IOException e) {
//			e.printStackTrace();
//		}
//		return durationInSeconds;
//
//	}

}
