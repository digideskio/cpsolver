package net.sf.cpsolver.coursett.constraint;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import net.sf.cpsolver.coursett.Constants;
import net.sf.cpsolver.coursett.model.Lecture;
import net.sf.cpsolver.coursett.model.Placement;
import net.sf.cpsolver.coursett.model.TimeLocation.IntEnumeration;
import net.sf.cpsolver.ifs.model.Constraint;
import net.sf.cpsolver.ifs.model.Value;
import net.sf.cpsolver.ifs.model.Variable;
import net.sf.cpsolver.ifs.util.DataProperties;
import net.sf.cpsolver.ifs.util.ToolBox;

/** Spread given set of classes in time as much as possible. 
 * See {@link DepartmentSpreadConstraint} for more details. 
 * 
 * @version
 * CourseTT 1.1 (University Course Timetabling)<br>
 * Copyright (C) 2006 Tomas Muller<br>
 * <a href="mailto:muller@ktiml.mff.cuni.cz">muller@ktiml.mff.cuni.cz</a><br>
 * Lazenska 391, 76314 Zlin, Czech Republic<br>
 * <br>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <br><br>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <br><br>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

public class SpreadConstraint extends Constraint implements WeakeningConstraint {
    private static org.apache.log4j.Logger sLogger = org.apache.log4j.Logger.getLogger(DepartmentSpreadConstraint.class);
    private static java.text.DecimalFormat sDoubleFormat = new java.text.DecimalFormat("0.00",new java.text.DecimalFormatSymbols(Locale.US));
    private static java.text.DecimalFormat sIntFormat = new java.text.DecimalFormat("0",new java.text.DecimalFormatSymbols(Locale.US));
    private int iMaxCourses[][] = null;
    private int iCurrentPenalty = 0;
    private int iMaxAllowedPenalty = 0;
    
    private boolean iInitialized = false;
    private int[][] iNrCourses = null;
    private Vector [][] iCourses = null;
    private double iSpreadFactor = 1.20;
    private int iUnassignmentsToWeaken = 250;
    private long iUnassignment = 0;
    private String iName = null;
    
    public static boolean USE_MOST_IMPROVEMENT_ADEPTS = false;
    
    public SpreadConstraint(String name, double spreadFactor, int unassignmentsToWeaken, boolean interactiveMode) {
        iName = name;
        iSpreadFactor = spreadFactor;
        iUnassignmentsToWeaken = unassignmentsToWeaken;
        iNrCourses = new int[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        iCourses = new Vector[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        if (interactiveMode)
            iUnassignmentsToWeaken = 0;
        for (int i=0;i<iNrCourses.length;i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                iNrCourses[i][j] = 0;
                iCourses[i][j]=new Vector(10,5);
            }
        }
    }
    
    public SpreadConstraint(DataProperties config, String name) {
    	this(
    			name, 
    			config.getPropertyDouble("Spread.SpreadFactor",1.20),
    			config.getPropertyInt("Spread.Unassignments2Weaken", 250),
    			config.getPropertyBoolean("General.InteractiveMode", false)
    			);
    }
    
    /** Initialize constraint (to be called after all variables are added to this constraint) */
    public void init() {
        if (iInitialized) return;
        double histogramPerDay[][] = new double[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        iMaxCourses = new int[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++)
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++)
                histogramPerDay[i][j]=0.0;
        int totalUsedSlots = 0;
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lecture = (Lecture)e.nextElement();
            Placement firstPlacement = (lecture.values().isEmpty()?null:(Placement)lecture.values().firstElement());
            if (firstPlacement!=null) {
                totalUsedSlots += firstPlacement.getTimeLocation().getNrSlotsPerMeeting()*firstPlacement.getTimeLocation().getNrMeetings();
            }
            for (Enumeration e2=lecture.values().elements();e2.hasMoreElements();) {
                Placement p = (Placement)e2.nextElement();
                int firstSlot = p.getTimeLocation().getStartSlot();
                if (firstSlot>Constants.DAY_SLOTS_LAST) continue;
                int endSlot = firstSlot+p.getTimeLocation().getNrSlotsPerMeeting()-1;
                if (endSlot<Constants.DAY_SLOTS_FIRST) continue;
                for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
                    int dayCode = p.getTimeLocation().getDayCode();
                    for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                        if ((dayCode & Constants.DAY_CODES[j])!=0) {
                            histogramPerDay[i-Constants.DAY_SLOTS_FIRST][j] += 1.0 / lecture.values().size();
                        }
                    }
                }
            }
        }
        //System.out.println("Histogram for department "+iDepartment+":");
        double threshold = iSpreadFactor*((double)totalUsedSlots/(Constants.NR_DAYS_WEEK*Constants.SLOTS_PER_DAY_NO_EVENINGS));
        //System.out.println("Threshold["+iDepartment+"] = "+threshold);
        int totalAvailableSlots = 0;
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++) {
            //System.out.println("  "+fmt(i+1)+": "+fmt(histogramPerDay[i]));
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                iMaxCourses[i][j]=(int)(0.999+(histogramPerDay[i][j]<=threshold?iSpreadFactor*histogramPerDay[i][j]:histogramPerDay[i][j]));
                totalAvailableSlots += iMaxCourses[i][j];
            }
        }
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lecture = (Lecture)e.nextElement();
            Placement placement = (Placement)lecture.getAssignment();
            if (placement==null) continue;
            int firstSlot = placement.getTimeLocation().getStartSlot();
            if (firstSlot>Constants.DAY_SLOTS_LAST) continue;
            int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
            if (endSlot<Constants.DAY_SLOTS_FIRST) continue;
            for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
                for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                    int dayCode = Constants.DAY_CODES[j];
                    if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                        iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]++;
                        iCourses[i-Constants.DAY_SLOTS_FIRST][j].addElement(placement);
                    }
                }
            }
        }
        iCurrentPenalty = 0;
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
            	iCurrentPenalty += Math.max(0,iNrCourses[i][j]-iMaxCourses[i][j]);
            }
        }
        iMaxAllowedPenalty = iCurrentPenalty;
        //System.out.println("Initial penalty = "+fmt(iMaxAllowedPenalty));
        iInitialized = true;
    }
    
    public Placement getAdept(Placement placement, int[][] nrCourses, Set conflicts) {
        Placement adept = null;
        int improvement = 0;
        
        //take uncommitted placements first
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lect = (Lecture)e.nextElement();
            if (lect.isCommitted()) continue;
            Placement plac = (Placement)lect.getAssignment();
            if (plac==null || plac.equals(placement) || conflicts.contains(plac)) continue;
            int imp = getPenaltyIfUnassigned(plac,nrCourses);
            if (imp==0) continue;
            if (adept==null || imp>improvement) {
                adept = plac; improvement = imp;
            }
        }
        if (adept!=null) return adept;
        
        // no uncommitted placement found -- take commiteed one
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lect = (Lecture)e.nextElement();
            if (!lect.isCommitted()) continue;
            Placement plac = (Placement)lect.getAssignment();
            if (plac==null || plac.equals(placement) || conflicts.contains(plac)) continue;
            int imp = getPenaltyIfUnassigned(plac,nrCourses);
            if (imp==0) continue;
            if (adept==null || imp>improvement) {
                adept = plac; improvement = imp;
            }
        }
        
        return adept;
    }
    
    private Set[] getAdepts(Placement placement, int[][] nrCourses, Set conflicts) {
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return null;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return null;
    	HashSet adepts[] = new HashSet[] {new HashSet(),new HashSet()};
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0 && nrCourses[i-Constants.DAY_SLOTS_FIRST][j]>=iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) {
                	for (Enumeration e=iCourses[i-Constants.DAY_SLOTS_FIRST][j].elements();e.hasMoreElements();) {
                		Placement p = (Placement)e.nextElement();
                		if (conflicts.contains(p)) continue;
                		if (p.equals(placement)) continue;
                		adepts[((Lecture)p.variable()).isCommitted()?1:0].add(p);
                	}
                }
            }
        }
        return adepts;
        //sLogger.debug("  -- adept "+adept+" selected, penalty will be decreased by "+improvement);
    }
    
    private int getPenaltyIfUnassigned(Placement placement, int[][] nrCourses)  {
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return 0;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return 0;
        int penalty = 0;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0 &&
                    nrCourses[i-Constants.DAY_SLOTS_FIRST][j]>iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) penalty ++;
            }
        }
        return penalty;
    }

    private int tryUnassign(Placement placement, int[][] nrCourses) {
        //sLogger.debug("  -- trying to unassign "+placement);
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return 0;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return 0;
        int improvement = 0;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                    if (nrCourses[i-Constants.DAY_SLOTS_FIRST][j]>iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) improvement++;
                    nrCourses[i-Constants.DAY_SLOTS_FIRST][j]--;
                }
            }
        }
        //sLogger.debug("  -- penalty is decreased by "+improvement);
        return improvement;
    }

    private int tryAssign(Placement placement, int[][] nrCourses) {
        //sLogger.debug("  -- trying to assign "+placement);
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return 0;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return 0;
        int penalty = 0;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                    nrCourses[i-Constants.DAY_SLOTS_FIRST][j]++;
                    if (nrCourses[i-Constants.DAY_SLOTS_FIRST][j]>iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) penalty++;
                }
            }
        }
        //sLogger.debug("  -- penalty is incremented by "+penalty);
        return penalty;
    }

    public void computeConflicts(Value value, Set conflicts) {
        if (!iInitialized || iUnassignmentsToWeaken==0) return;
        Placement placement = (Placement)value;
        int penalty = iCurrentPenalty + getPenalty(placement);
        if (penalty<=iMaxAllowedPenalty) return;
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return;
        //sLogger.debug("-- computing conflict for value "+value+" ... (penalty="+iCurrentPenalty+", penalty with the value="+penalty+", max="+iMaxAllowedPenalty+")");
        int[][] nrCourses = new int[iNrCourses.length][Constants.NR_DAYS_WEEK];
        for (int i=0;i<iNrCourses.length;i++)
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++)
                nrCourses[i][j] = iNrCourses[i][j];
        tryAssign(placement, nrCourses);
        //sLogger.debug("  -- nrCurses="+fmt(nrCourses));
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lect = (Lecture)e.nextElement();
            if (conflicts.contains(lect)) {
                penalty -= tryUnassign((Placement)lect.getAssignment(), nrCourses);
            }
            if (penalty<=iMaxAllowedPenalty) return;
        }
        if (USE_MOST_IMPROVEMENT_ADEPTS) {
        	while (penalty>iMaxAllowedPenalty) {
        		Placement plac = getAdept(placement, nrCourses, conflicts);
        		if (plac==null) break;
        		conflicts.add(plac);
        		penalty -= tryUnassign(plac, nrCourses);
        	}
        } else {
            if (penalty>iMaxAllowedPenalty) {
            	Set adepts[] = getAdepts(placement, nrCourses, conflicts);
            	for (int i=0;penalty>iMaxAllowedPenalty && i<adepts.length;i++) {
            		while (!adepts[i].isEmpty() && penalty>iMaxAllowedPenalty) {
            			Placement plac = (Placement)ToolBox.random(adepts[i]);
            			adepts[i].remove(plac);
            			conflicts.add(plac);
            			//sLogger.debug("  -- conflict "+lect.getAssignment()+" added");
            			penalty -= tryUnassign(plac, nrCourses);
            		}
            	}
            }
        }
    }
    
    public boolean inConflict(Value value) {
        if (!iInitialized || iUnassignmentsToWeaken==0) return false;
        Placement placement = (Placement)value;
            return getPenalty(placement)+iCurrentPenalty>iMaxAllowedPenalty;
    }
    
    public boolean isConsistent(Value value1, Value value2) {
        if (!iInitialized || iUnassignmentsToWeaken==0) return true;
        Placement p1 = (Placement)value1;
        Placement p2 = (Placement)value2;
        if (!p1.getTimeLocation().hasIntersection(p2.getTimeLocation())) return true;
        int firstSlot = Math.max(p1.getTimeLocation().getStartSlot(),p2.getTimeLocation().getStartSlot());
        if (firstSlot>Constants.DAY_SLOTS_LAST) return true;
        int endSlot = Math.min(p1.getTimeLocation().getStartSlot()+p1.getTimeLocation().getNrSlotsPerMeeting()-1,p2.getTimeLocation().getStartSlot()+p2.getTimeLocation().getNrSlotsPerMeeting()-1);
        if (endSlot<Constants.DAY_SLOTS_FIRST) return true;
        int penalty = 0;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & p1.getTimeLocation().getDayCode()) != 0 && (dayCode & p2.getTimeLocation().getDayCode()) != 0) {
                    penalty += Math.max(0,2-iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]);
                }
            }
        }
        return (penalty<iMaxAllowedPenalty);
    }
    
    public void weaken() {
        if (!iInitialized || iUnassignmentsToWeaken==0) return;
        iUnassignment ++;
        if (iUnassignment%iUnassignmentsToWeaken==0)
            iMaxAllowedPenalty ++;
    }
    
    public void assigned(long iteration, Value value) {
        super.assigned(iteration, value);
        if (!iInitialized) return;
        Placement placement = (Placement)value;
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                    iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]++;
                    if (iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]>iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) iCurrentPenalty++;
                    iCourses[i-Constants.DAY_SLOTS_FIRST][j].addElement(value);
                }
            }
        }
    }
    
    public void unassigned(long iteration, Value value) {
        super.unassigned(iteration, value);
        if (!iInitialized) return;
        Placement placement = (Placement)value;
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return;
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return;
        for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                int dayCode = Constants.DAY_CODES[j];
                if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                    if (iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]>iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]) iCurrentPenalty--;
                    iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]--;
                    iCourses[i-Constants.DAY_SLOTS_FIRST][j].removeElement(value);
                }
            }
        }
    }
    
    public String getName() { return iName; }
    
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Time Spread between ");
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Variable v = (Variable)e.nextElement();
            sb.append(v.getName());
            if (e.hasMoreElements()) sb.append(", ");
        }
        return sb.toString();
    }
    
    /** Department balancing penalty for this department */
    public int getPenalty() {
        if (!iInitialized) return getPenaltyEstimate();
        return iCurrentPenalty;
    }
    
    public int getPenaltyEstimate() {
        double histogramPerDay[][] = new double[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        int maxCourses[][] = new int[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        int nrCourses[][] = new int[Constants.SLOTS_PER_DAY_NO_EVENINGS][Constants.NR_DAYS_WEEK];
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++)
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++)
                histogramPerDay[i][j]=0.0;
        int totalUsedSlots = 0;
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lecture = (Lecture)e.nextElement();
            Placement firstPlacement = (lecture.values().isEmpty()?null:(Placement)lecture.values().firstElement());
            if (firstPlacement!=null) {
                totalUsedSlots += firstPlacement.getTimeLocation().getNrSlotsPerMeeting()*firstPlacement.getTimeLocation().getNrMeetings();
            }
            for (Enumeration e2=lecture.values().elements();e2.hasMoreElements();) {
                Placement p = (Placement)e2.nextElement();
                int firstSlot = p.getTimeLocation().getStartSlot();
                if (firstSlot>Constants.DAY_SLOTS_LAST) continue;
                int endSlot = firstSlot+p.getTimeLocation().getNrSlotsPerMeeting()-1;
                if (endSlot<Constants.DAY_SLOTS_FIRST) continue;
                for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
                    int dayCode = p.getTimeLocation().getDayCode();
                    for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                        if ((dayCode & Constants.DAY_CODES[j])!=0) {
                            histogramPerDay[i-Constants.DAY_SLOTS_FIRST][j] += 1.0 / lecture.values().size();
                        }
                    }
                }
            }
        }
        double threshold = iSpreadFactor*((double)totalUsedSlots/(Constants.NR_DAYS_WEEK*Constants.SLOTS_PER_DAY_NO_EVENINGS));
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
            	nrCourses[i][j]=0;
            	maxCourses[i][j]=(int)(0.999+(histogramPerDay[i][j]<=threshold?iSpreadFactor*histogramPerDay[i][j]:histogramPerDay[i][j]));
            }
        }
        int currentPenalty = 0;
        for (Enumeration e=variables().elements();e.hasMoreElements();) {
            Lecture lecture = (Lecture)e.nextElement();
            Placement placement = (Placement)lecture.getAssignment();
            if (placement==null) continue;
            int firstSlot = placement.getTimeLocation().getStartSlot();
            if (firstSlot>Constants.DAY_SLOTS_LAST) continue;
            int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
            if (endSlot<Constants.DAY_SLOTS_FIRST) continue;
            for (int i=Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);i<=Math.min(endSlot,Constants.DAY_SLOTS_LAST);i++) {
                for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
                    int dayCode = Constants.DAY_CODES[j];
                    if ((dayCode & placement.getTimeLocation().getDayCode()) != 0) {
                    	nrCourses[i-Constants.DAY_SLOTS_FIRST][j]++;
                    }
                }
            }
        }
        for (int i=0;i<Constants.SLOTS_PER_DAY_NO_EVENINGS;i++) {
            for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
            	currentPenalty += Math.max(0,nrCourses[i][j]-maxCourses[i][j]);
            }
        }
        iMaxAllowedPenalty = Math.max(iMaxAllowedPenalty,currentPenalty);
        return currentPenalty;
    }
    
    public int getMaxPenalty(Placement placement) {
    	int penalty = 0;
    	for (IntEnumeration e=placement.getTimeLocation().getSlots();e.hasMoreElements();) {
    		int slot = e.nextInt();
    		int day = slot/Constants.SLOTS_PER_DAY;
    		int time = slot%Constants.SLOTS_PER_DAY;
    		if (time<Constants.DAY_SLOTS_FIRST || time>Constants.DAY_SLOTS_LAST) continue;
    		if (day>=Constants.NR_DAYS_WEEK) continue;
    		int dif = iNrCourses[time-Constants.DAY_SLOTS_FIRST][day]-iMaxCourses[time-Constants.DAY_SLOTS_FIRST][day];
    		if (dif>penalty) penalty = dif;
    	}
    	return penalty;
    }
    
    /** Department balancing penalty of the given placement */
    public int getPenalty(Placement placement) {
        if (!iInitialized) return 0;
        int firstSlot = placement.getTimeLocation().getStartSlot();
        if (firstSlot>Constants.DAY_SLOTS_LAST) return 0;
        Placement initialPlacement = (Placement)placement.variable().getAssignment();
        int endSlot = firstSlot+placement.getTimeLocation().getNrSlotsPerMeeting()-1;
        if (endSlot<Constants.DAY_SLOTS_FIRST) return 0;
        int penalty = 0;
        int min = Math.max(firstSlot,Constants.DAY_SLOTS_FIRST);
        int max = Math.min(endSlot,Constants.DAY_SLOTS_LAST);
        for (int j=0;j<Constants.NR_DAYS_WEEK;j++) {
        	int dayCode = Constants.DAY_CODES[j];
        	if ((dayCode & placement.getTimeLocation().getDayCode()) == 0) continue;
        	for (int i=min;i<=max;i++) {
                if (iNrCourses[i-Constants.DAY_SLOTS_FIRST][j]>=iMaxCourses[i-Constants.DAY_SLOTS_FIRST][j]+(initialPlacement==null?0:iCourses[i-Constants.DAY_SLOTS_FIRST][j].contains(initialPlacement)?1:0))
                	penalty ++;
            }
        }
        return penalty;
    }
        
    private String fmt(double value) {
        return sDoubleFormat.format(value);
    }
    
    private String fmt(double[] values) {
        if (values==null) return null;
        StringBuffer sb = new StringBuffer("[");
        for (int i=0;i<Math.min(5,values.length);i++)
            sb.append(i==0?"":",").append(sDoubleFormat.format(values[i]));
            sb.append("]");
            return sb.toString();
    }
    
    private String fmt(double[][] values) {
        if (values==null) return null;
        StringBuffer sb = new StringBuffer("[");
        for (int i=0;i<values.length;i++) {
            sb.append(i==0?"":",").append(fmt(values[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String fmt(int value) {
        return sIntFormat.format(value);
    }
    
    private String fmt(int[] values) {
        if (values==null) return null;
        StringBuffer sb = new StringBuffer("[");
        for (int i=0;i<Math.min(5,values.length);i++)
            sb.append(i==0?"":",").append(sIntFormat.format(values[i]));
            sb.append("]");
            return sb.toString();
    }
    
    private String fmt(int[][] values) {
        if (values==null) return null;
        StringBuffer sb = new StringBuffer("[");
        for (int i=0;i<values.length;i++) {
            sb.append(i==0?"":",").append(fmt(values[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    public int[][] getMaxCourses() { return iMaxCourses; }
    public int[][] getNrCourses() { return iNrCourses; }
    public Vector[][] getCourses() { return iCourses; }
    
    public void addVariable(Variable variable) {
    	Lecture lecture = (Lecture)variable;
    	if (lecture.canShareRoom()) {
    		for (Iterator i=lecture.groupConstraints().iterator();i.hasNext();) {
    			GroupConstraint gc = (GroupConstraint)i.next();
    			if (gc.getType()==GroupConstraint.TYPE_MEET_WITH) {
    				if (gc.variables().indexOf(lecture)>0) return;
    			}
    		}
    	}
    	super.addVariable(variable);
    }
}
