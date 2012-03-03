package internetz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import repast.simphony.context.Context;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ScheduledMethod;
import repast.simphony.engine.watcher.Watch;
import repast.simphony.engine.watcher.WatcherTriggerSchedule;
import repast.simphony.parameter.Parameters;
import repast.simphony.random.RandomHelper;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.util.ContextUtils;

public class Agent {

	Parameters param = RunEnvironment.getInstance().getParameters();
	String algo = (String)param.getValue("filteringalgo");
	int ownlinks = (Integer)param.getValue("linkswithown");
	int maxBeliefs = (Integer)param.getValue("maxbelief");
	int status = 0;
	int grp;
	Network memory;
	Network artimeme;
	Network<Artifact> artifact;
	Network belief;
	Network<Agent> sns;

	boolean isPublisher;
	private int readingCapacity;
	private ArrayList<Artifact> bookmarks = new ArrayList();
	private ArrayList<Artifact> creatures = new ArrayList();
	private ArrayList<Artifact> voted = new ArrayList();
	private ArrayList<Artifact> shared = new ArrayList();


	public Agent() {


		// this.readingCapacity = readingCapacity;
		// this.isPublisher = isPublisher;
		this.bookmarks = bookmarks;
		this.creatures = creatures;
		this.maxBeliefs = maxBeliefs;
		this.status = status;
		this.grp = grp;

		// This is now moved in the context:
		// RandomHelper.createPoisson(maxbeliefs/2);
		// int howmany = RandomHelper.getPoisson().nextInt();

	}

	public void setReadingCapacity(int readingCapacity) {
		this.readingCapacity = readingCapacity;

	}

	public void setGroup(int group) {
		this.grp = group;
	}

	public int getGroup() {
		return this.grp;
	}

	public void setPublisher(boolean isPublisher) {
		this.isPublisher = isPublisher;

	}

	@ScheduledMethod(start = 1, interval = 1)
	public void step() {
		
		Context context = (Context)ContextUtils.getContext(this);
		belief = (Network)context.getProjection("beliefs");
		memory = (Network)context.getProjection("memorys");
		artimeme = (Network)context.getProjection("artimemes");
		artifact = (Network<Artifact>)context.getProjection("artifacts");
		sns = (Network)context.getProjection("twitter");
	
		int time = (int) RunEnvironment.getInstance().getCurrentSchedule().getTickCount();
		
		// Publishers have their chance to publish
		if (isPublisher) {
			if (status == 0) publish();
			status+=changeStatus();
		}

		// Everybody explores
		explore();

		// Every now and then we update stuff
		if (time%5==0) corrupt(bookmarks, this.maxBeliefs);
		
		updateblfs();
		updatememz();
			
		// These ones we do with scheduling
		//corrupt(belief, this.maxBeliefs);
		//corrupt(memory, this.maxBeliefs);
	}

	public void explore() {
		Context<Object> context = (Context)ContextUtils.getContext(this);	
		int howmany = RandomHelper.nextIntFromTo(0, this.readingCapacity);
		if (algo.equals("random")) {
			Iterable localarts = context.getRandomObjects(Artifact.class, howmany);
			while (localarts.iterator().hasNext()) {
				Artifact localart = (Artifact)localarts.iterator().next();
				localart.addView();
				if (!localart.author.equals(this)&&!bookmarks.contains(localart)) {
					read(localart);
					bookmarks.add(localart);
				}
			}
		} else {
			if (algo.equals("none")) {
				if (!bookmarks.isEmpty()) exploreByLinks(howmany+1, bookmarks);
				else {
					ArrayList allarts = getTransformedIteratorToArrayList((context.getObjects(Artifact.class)).iterator());
					if (allarts.size() > 0) exploreByLinks(howmany, allarts);
				}
			} else explorebymemes();
		}
	}

	public void exploreByLinks(int capacity, ArrayList startingset) {
		int reads = 0;
		//int frustration = 0;
		int whichone = 0;
		Artifact nowreading = null;
		int size = startingset.size();
		if (size>0) {
			whichone = 0;
			nowreading = (Artifact) startingset.get(whichone);
			//if (size<capacity) capacity=size;
			while (reads < capacity) {
				nowreading.addView();  // the artifact gets a page view
				if (!nowreading.author.equals(this)&&!bookmarks.contains(nowreading)) {
					read(nowreading);
					System.out.println("Yeah, I read");
					reads++;
					bookmarks.add(nowreading);
					if (artifact.getOutDegree(nowreading)>0) nowreading = (Artifact) artifact.getRandomSuccessor(nowreading);
					else {
						whichone++;
						if (whichone==size) break;
						nowreading = (Artifact) startingset.get(whichone);
					}
				} else {
					if (artifact.getOutDegree(nowreading)>1) {
						nowreading = nowreading = (Artifact) artifact.getRandomSuccessor(nowreading);
						// System.out.println("There are links. I follow....");
						System.out.println("Follow a link");
					} else {
						whichone++;
						if (whichone==size) break;
						nowreading = (Artifact) startingset.get(whichone);
						// System.out.println(nowreading);
						//frustration++;
						System.out.println("Random artifact");
					}
				}
			}
		}
	}

	public void explorebymemes() {
		Context<Object> context = (Context)ContextUtils.getContext(this);	
		Meme currentmeme = (Meme) belief.getRandomAdjacent(this);
		int howmany = RandomHelper.nextIntFromTo(1, readingCapacity);
		if (algo.equals("mix")) howmany/=2;
		if (algo.equals("redditmix")) howmany/=3;
		ArrayList all = getTransformedIteratorToArrayList(artimeme.getAdjacent(currentmeme).iterator());
		if (algo.equals("pagerank")||algo.equals("mix")||algo.equals("redditmix")) Collections.sort(all, new PageRankComparator());
		// if (algo.equals("popularity")) Collections.sort(all, new PopularityComparator());
		suck(howmany,all);
		if (algo.equals("mix")||algo.equals("redditmix")) exploreByLinks(howmany+1,all);
		if (algo.equals("redditmix")) {
			// Uncomment the following if you want reddit to feed the most voted artifacts in (i.e. reddit frontpage)
			// Otherwise the agent will read most voted artifacts only relative to his meme of interest (i.e. a subreddit)
			all = getTransformedIteratorToArrayList(context.getObjects(Artifact.class).iterator());
			Collections.sort(all, new VoteComparator());
			suck(howmany+2,all);
		}
	}

	public void suck(int capacity, ArrayList startingset) {
		int i=0; // read artifacts
		int a=0; // artifacts not read because unsuitable
		int size = startingset.size();
		if (size < capacity) capacity = size;
		while (i < capacity) {
			// It need not be a creature of the reader nor recently bookmarked
			Artifact arti = (Artifact) startingset.get(i);
			if (!arti.author.equals(this)&&!bookmarks.contains(arti)) {
				read(arti);
				arti.addView();  // the artifact gets a page view
				bookmarks.add(arti);
				i++;
			} else a++;
			if (a+i==size) break;
		}
	}

	public void read (Artifact arti) {
		double sticksInMem = (Double) param.getValue("sticksInMem");
		Iterator memez = arti.getMemes();
		int artiTotalMemes = arti.totalMemesInvested();
		boolean known = false;
		int howsimilar = 0;
		while (memez.hasNext()) {
			Meme thismeme = (Meme) memez.next();				
			if (belief.isAdjacent(this, thismeme)) {
				known = true;
				howsimilar++;
			}
		}
		if (known) {
			sticksInMem+=0.25;
			double prob = (howsimilar/artiTotalMemes); //+0.05 which was here ;
			voteAndLink(arti,prob);
		} else decreaseSocial(arti.getAuthor()); // else sticksInMem-=0.25; 

		memez = arti.getMemes();
		while (memez.hasNext()) {
			Meme thismeme = (Meme) memez.next();	
			if (belief.isAdjacent(this, thismeme)) {
				RepastEdge lnk = belief.getEdge(this, thismeme);
				double wght = lnk.getWeight();
				if (wght+0.1>1) lnk.setWeight(1);
				else lnk.setWeight(wght+0.1);
			}
			if (memory.isAdjacent(this, thismeme)) {
				RepastEdge lnk = memory.getEdge(this, thismeme);
				double wght = lnk.getWeight();
				lnk.setWeight(wght+0.1);
				//System.out.println("I'm adding to existing memory");
				if (lnk.getWeight()>=1) {
					lnk.setWeight(0.5);
					if (!belief.containsEdge(lnk)) {
						belief.addEdge(lnk);
						//System.out.println("I'm adding a new belief");
					} else {
						//System.out.println("I'm adding to an existing belief + memory");
						Meme otherend = (Meme) lnk.getTarget();
						double wght2 = belief.getEdge(this, otherend).getWeight();
						belief.getEdge(this, otherend).setWeight(wght2+0.1);
					}
				}
			}
			else {
				if (RandomHelper.nextDoubleFromTo(0, 1)<sticksInMem){
					memory.addEdge(this, thismeme, 0.5);
					//System.out.println("I'm adding a new memory");
				}
			}
		}
	}

	public void publish() {
		Context<Object> context = (Context)ContextUtils.getContext(this);		
		Artifact newArt = new Artifact(this, 0);
		newArt.views = 0;
		newArt.votes = 0;
		newArt.shares = 0;
		newArt.birthday = RunEnvironment.getInstance().getCurrentSchedule().getTickCount();
		newArt.id = context.getObjects(Artifact.class).size() + 1;
		// // System.out.println("Just created the artifact #: " + newArt.id);
		context.add(newArt);
		creatures.add(newArt);
		shared.add(newArt);
		// System.out.println("We have " + creatures.size() + " creatures");

		// WARNINGWARNING: magic number to be replaced here
		int mymemes = (int) ((belief.getDegree(this)*0.20)+1);
		int howmanymemes = RandomHelper.nextIntFromTo(0, mymemes); 
		for (int i=0; i<howmanymemes; i++) {
			Meme investingmeme = (Meme) belief.getRandomAdjacent(this);
			Meme investingmeme2 = (Meme) belief.getRandomAdjacent(this);
			if (belief.getEdge(this, investingmeme2).getWeight() > belief.getEdge(this, investingmeme).getWeight()) investingmeme=investingmeme2; 
			artimeme.addEdge(investingmeme, newArt);
			// System.out.println("I have just put " + howmanymemes +" memes in the artifact");
		}

		// MAGIC NUMBER HERE!
		if (creatures.size() > 5) {
			linkWithOwn(newArt); 
		}
		if (!bookmarks.isEmpty()) {
			link(newArt);
		} else {
			linkOnce(newArt);
		}
	}

	// The first time we link with a random artifact (if there is one)
	public void linkOnce(Artifact newart) {
		Context<Object> context = (Context)ContextUtils.getContext(this);
		int howmany = context.getObjects(Artifact.class).size();
		if (howmany > 0) {
			Artifact arti = (Artifact) context.getRandomObjects(Artifact.class, 1).iterator().next();
			if (!arti.equals(newart)) {
				newart.buildLink(arti);
				System.out.println("Building a randomlink");
				read(arti);
			}
		}
	}

	public void linkWithOwn(Artifact arti) {
		ArrayList<Artifact> towhom = (getMostSimilar(creatures, arti));
		int similars = towhom.size();
		int howmany = 0;
		if (similars >= ownlinks) howmany = ownlinks;
		else howmany = similars;
		for (int i=0; i<=howmany-1; i++) {
			Artifact oldart = towhom.get(i);
			arti.buildLink(oldart);
			oldart.buildLink(arti);
		}
	}

	// Memetic similarity extractor 
	public ArrayList<Artifact> getMostSimilar(ArrayList<Artifact> list, Artifact source) {
		// System.out.println("Hello. I'm now looking for the most similar artifact");
		ArrayList<Artifact> mostSimilarArtifacts = new ArrayList();
		int oldMostSimilarMemes = 0;
		ArrayList newlist = list;
		if (newlist.contains(source)) newlist.remove(source);
		int listSize = newlist.size();
		int i = 0;
		while (i<listSize) {
			Artifact oldart = (Artifact) newlist.get(i);
			Iterator oldArtifactMemes = oldart.getMemes();
			int newMostSimilarMemes = 0;
			while (oldArtifactMemes.hasNext()) if (artimeme.isAdjacent(oldArtifactMemes.next(),source)) newMostSimilarMemes++;
			if (oldMostSimilarMemes == newMostSimilarMemes) mostSimilarArtifacts.add(oldart);
			else {
				if (newMostSimilarMemes > oldMostSimilarMemes) {
					mostSimilarArtifacts.clear();
					mostSimilarArtifacts.add(oldart);
					oldMostSimilarMemes = newMostSimilarMemes;
				}
			}
			i++;
		}
		return mostSimilarArtifacts;
	}

	public ArrayList getTransformedIteratorToArrayList(Iterator itr){
		ArrayList arr = new ArrayList();
		while(itr.hasNext()){
			arr.add(itr.next());
		}
		return arr;
	}

	public void link(Artifact newart) { // RECHECK THIS
		ArrayList mostsimilar = getMostSimilar(bookmarks, newart);
		int size = mostsimilar.size();
		// System.out.println("I have to link the artifact with " + size + " others");
		for (int i=0;i<size;i++) {
			Artifact arti = (Artifact) mostsimilar.get(i);
			newart.buildLink(arti);
			// System.out.println("I have linked the artifact with a bookmark");
		}
	}

	
		//public void corrupt(Network net, int max) {
		//ArrayList alledges = getTransformedIteratorToArrayList(net.getEdges(this).iterator());
		//int alledgesNo = alledges.size();
		//int howmanydeaths = 0;
		//Collections.sort(alledges, new InverseWeightComparator());
		//if (alledgesNo > max) {
		//	howmanydeaths = (alledgesNo-max)-1;
		//	if (alledges.size() >= howmanydeaths) {
		//		for (int i=0; i<howmanydeaths; i++) {
		//			net.removeEdge((RepastEdge) alledges.get(i));
		//		}
		//	}
		//}
		//else {
		//	if (alledges.size()>2) {
		//		net.removeEdge((RepastEdge) alledges.get(alledgesNo-1));
		//	}
		//}
	

	public void corrupt(ArrayList list, int max) {
		int size = list.size();
		int howmanydeaths = 0;
		if (size>max) {
			howmanydeaths = size-max;
			for (int i=0; i<howmanydeaths-1; i++) {
				list.remove(i);
			}
		}// else if (size>2) list.remove(0);
	}

	public void voteAndLink(Artifact arti, double probability) {
		double recipro = (Double) param.getValue("avgReciprocating");
		Agent artiAuthor = arti.getAuthor();
		if ((RandomHelper.nextDoubleFromTo(0, 1) < probability)) {
			if (!voted.contains(arti)) {
				arti.addVote();
				voted.add(arti);
			}
			if (!sns.isPredecessor(this, artiAuthor)) {
				sns.addEdge(this, artiAuthor, 0.1);
				if (RandomHelper.nextDoubleFromTo(0, 1)<=(recipro+0.20)) sns.addEdge(artiAuthor, this);
			} else {
				shared.add(arti);
				arti.addShare();
				//System.out.println("I shared");
			}
		}
		else decreaseSocial(artiAuthor);
	}
	
	
	public void decreaseSocial(Agent artiAuthor) {
		if (sns.isAdjacent(this, artiAuthor)) {
			RepastEdge link = sns.getEdge(this, artiAuthor);
			double weight = link.getWeight();
			if (weight > 0.1) link.setWeight(weight-0.1);
		}
	}
	


	//@ScheduledMethod(start = 1, interval = 1)
	//public void updatebeliefs() {
		//ArrayList memz = getTransformedIteratorToArrayList(memory.getEdges(this).iterator());
		//if (memz.size() > 1) {
		//	Collections.sort(memz, new WeightComparator());
		//	RepastEdge max = (RepastEdge) memz.get(0);
		//	double maxweight = max.getWeight();
		//	for (int i=0; i<memz.size(); i++) {
		//		RepastEdge link = (RepastEdge) memz.get(i);
		//		double wght = link.getWeight(); 
		//		if (wght >= maxweight) {
		//			Meme meme = (Meme) link.getTarget(); // WARNING: Using 'target' on undirected network
		//			if (belief.isAdjacent(this, meme)) {
		//				RepastEdge thisbelief = belief.getEdge(this, meme);
		//				if (thisbelief.getWeight() < 1) thisbelief.setWeight(thisbelief.getWeight() + 0.1);
		//			} else {
		//				if (link.getWeight()>0.5) link.setWeight(0.5);
		//				belief.addEdge(this, meme, 0.5);
		//			}
		//		} else break;
		//	}

			// if (ispublisher) { 
			//	relink(meme);		
			// We now have memetic similarity in linkwithown(), but should be doing periodic relinking anyway...
			// }
		//}
		
		
	//}
	
	
	public void updateblfs() {
		//Network belief = (Network)getProjection("beliefs");
		ArrayList blfs = getTransformedIteratorToArrayList(belief.getEdges(this).iterator());
		for (int i=0;i<blfs.size();i++) {
			RepastEdge blf = (RepastEdge) blfs.get(i);
			double wght = blf.getWeight();
			if (wght<=0) {
				if (RandomHelper.nextDoubleFromTo(0, 1)>0.50) belief.removeEdge(blf);
				//System.out.println("Removed a belief");
				//System.out.println("belief Weight now "+ blf.getWeight());
			} else blf.setWeight(wght-0.001);
		}
	}
	
	
	public void updatememz() {
		//Network memory = (Network)getProjection("memorys");
		ArrayList mmrs = getTransformedIteratorToArrayList(memory.getEdges(this).iterator());
		for (int i=0;i<mmrs.size();i++) {
			RepastEdge mmr = (RepastEdge) mmrs.get(i);
			double wgt = mmr.getWeight();
			if (wgt<=0) {
				if (RandomHelper.nextDoubleFromTo(0, 1)>0.50) memory.removeEdge(mmr);
			} else mmr.setWeight(wgt-0.001);	
		}
	}

	public int changeStatus() {
		if (RandomHelper.nextDoubleFromTo(0, 1) > 0.5) return 1;
		return -1;
	}

	public double getSilo() {
		int E=0;
		int I=0;
		//System.out.println("My memes are "+ belief.getDegree(this));
		Iterator mybeliefs = belief.getAdjacent(this).iterator();
		//if (mybeliefs.equals(null)) System.out.println("beliefs null");
		while (mybeliefs.hasNext()) {
			Meme meme = (Meme) mybeliefs.next();
			if (meme.getGrp()!=this.getGroup()) E++;
			else I++;
		}
		return (E-I)/belief.getDegree(this);
	}

	@Watch(watcheeClassName = "internetz.Agent", watcheeFieldNames = "shared", query = "linked_to['sns']", 
			whenToTrigger = WatcherTriggerSchedule.LATER, scheduleTriggerDelta = 1, scheduleTriggerPriority = 0)
	public void newShare(Agent friend) {
		int which = friend.shared.size();
		double prob = 0.10;
		Artifact arti = friend.shared.get(which-1);
		if (sns.getEdge(this, friend).getWeight()>0.5) prob = 0.50;
		if (RandomHelper.nextDoubleFromTo(0, 1)<=prob) read(arti);
	}
}