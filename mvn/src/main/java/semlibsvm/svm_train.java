package semlibsvm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.Vector;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import semlibsvm.libsvm.svm;
import semlibsvm.libsvm.svm_model;
import semlibsvm.libsvm.svm_node;
import semlibsvm.libsvm.svm_parameter;
import semlibsvm.libsvm.svm_print_interface;
import semlibsvm.libsvm.svm_problem;

public class svm_train {
	private svm_parameter param;		// set by parse_command_line
	private svm_problem prob;		// set by read_problem
	private svm_model model;
	private String input_file_name;		// set by parse_command_line
	private String model_file_name;		// set by parse_command_line
	private String error_msg;
	private int cross_validation;
	private int nr_fold;
    private boolean semantic = false;

	private static svm_print_interface svm_print_null = new svm_print_interface()
	{
		public void print(String s) {}
	};

	private static void exit_with_help()
	{
		System.out.print(
		 "Usage: svm_train [options] training_set_file [model_file]\n"
		+"options:\n"
		+"-s svm_type : set type of SVM (default 0)\n"
		+"	0 -- C-SVC		(multi-class classification)\n"
		+"	1 -- nu-SVC		(multi-class classification)\n"
		+"	2 -- one-class SVM\n"
		+"	3 -- epsilon-SVR	(regression)\n"
		+"	4 -- nu-SVR		(regression)\n"
		+"-t kernel_type : set type of kernel function (default 2)\n"
		+"	0 -- linear: u'*v\n"
		+"	1 -- polynomial: (gamma*u'*v + coef0)^degree\n"
		+"	2 -- radial basis function: exp(-gamma*|u-v|^2)\n"
		+"	3 -- sigmoid: tanh(gamma*u'*v + coef0)\n"
		+"	4 -- precomputed kernel (kernel values in training_set_file)\n"
		+"	5 -- semantic kernel\n"
		+"-d degree : set degree in kernel function (default 3)\n"
		+"-g gamma : set gamma in kernel function (default 1/num_features)\n"
		+"-r coef0 : set coef0 in kernel function (default 0)\n"
		+"-c cost : set the parameter C of C-SVC, epsilon-SVR, and nu-SVR (default 1)\n"
		+"-n nu : set the parameter nu of nu-SVC, one-class SVM, and nu-SVR (default 0.5)\n"
		+"-p epsilon : set the epsilon in loss function of epsilon-SVR (default 0.1)\n"
		+"-m cachesize : set cache memory size in MB (default 100)\n"
		+"-e epsilon : set tolerance of termination criterion (default 0.001)\n"
		+"-h shrinking : whether to use the shrinking heuristics, 0 or 1 (default 1)\n"
		+"-b probability_estimates : whether to train a SVC or SVR model for probability estimates, 0 or 1 (default 0)\n"
		+"-wi weight : set the parameter C of class i to weight*C, for C-SVC (default 1)\n"
		+"-v n : n-fold cross validation mode\n"
		+"-f ontology_filename : ontology file name\n"
		+"-q : quiet mode (no outputs)\n"
		);
		System.exit(1);
	}

	private void do_cross_validation()
	{
		int i;
		int total_correct = 0;
		double total_error = 0;
		double sumv = 0, sumy = 0, sumvv = 0, sumyy = 0, sumvy = 0;
		double[] target = new double[prob.l];

		svm.svm_cross_validation(prob,param,nr_fold,target);
		if(param.svm_type == svm_parameter.EPSILON_SVR ||
		   param.svm_type == svm_parameter.NU_SVR)
		{
			for(i=0;i<prob.l;i++)
			{
				double y = prob.y[i];
				double v = target[i];
				total_error += (v-y)*(v-y);
				sumv += v;
				sumy += y;
				sumvv += v*v;
				sumyy += y*y;
				sumvy += v*y;
			}
			System.out.print("Cross Validation Mean squared error = "+total_error/prob.l+"\n");
			System.out.print("Cross Validation Squared correlation coefficient = "+
				((prob.l*sumvy-sumv*sumy)*(prob.l*sumvy-sumv*sumy))/
				((prob.l*sumvv-sumv*sumv)*(prob.l*sumyy-sumy*sumy))+"\n"
				);
		}
		else
		{
		    int true_positive = 0 ;
		    int true_negative = 0 ;
		    int false_positive = 0 ;
		    int false_negative = 0 ;
		    double threshold = 0.5 ;
		    for(i=0;i<prob.l;i++) {
			System.out.println("Target: "+target[i]);
			System.out.println("Prob: "+prob.y[i]);
			if ((target[i] < threshold) && (prob.y[i] == 0.0)) {
			    ++true_negative ;
			} else if ((target[i] >= threshold) && (prob.y[i] == 1.0)) {
			    ++true_positive ;
			} else if ((target[i] >= threshold) && (prob.y[i] == 0.0)) {
			    ++false_positive ;
			} else if ((target[i] < threshold) && (prob.y[i] == 1.0)) {
			    ++false_negative ;
			}
			if(target[i] == prob.y[i]) {
			    ++total_correct;
			}
		    }

		    // calc AUC
		    // this is ridicilously inefficient; rewrite when had more sleep
		    int tp = 0 ;
		    int tn = 0 ;
		    int fp = 0 ;
		    int fn = 0 ;
		    Map<Double, Double> aucmap = new TreeMap<Double, Double> () ; // fp -> tp
		    aucmap.put(0.,0.) ;
		    aucmap.put(1.,1.) ;
		    for (threshold = 0 ; threshold <= 1; threshold += 0.01) {
			for(i=0;i<prob.l;i++) {
			    if ((target[i] < threshold) && (prob.y[i] == 0.0)) {
				++tn ;
			    } else if ((target[i] >= threshold) && (prob.y[i] == 1.0)) {
				++tp ;
			    } else if ((target[i] >= threshold) && (prob.y[i] == 0.0)) {
				++fp ;
			    } else if ((target[i] < threshold) && (prob.y[i] == 1.0)) {
				++fn ;
			    }
			}
			double tpr = 1.0*tp / (tp + fn) ;
			double fpr = 1.0*fp / (fp + tn) ;
			aucmap.put(fpr, tpr) ;
		    }
		    double auc = 0.0 ;
		    double olda = 0.0 ;
		    double oldb = 0.0 ;
		    for (double a : aucmap.keySet()) {
			double b = aucmap.get(a) ;
			auc += (b-oldb)*(a-olda)/2 ;
		    }
		    System.out.println(aucmap.toString()) ;
		    System.out.println("AUC: "+auc);
		    System.out.println("True positives: "+true_positive) ;
		    System.out.println("True negative: "+true_negative) ;
		    System.out.println("False positives: "+false_positive) ;
		    System.out.println("False negatives: "+false_negative) ;
		    double precision = 1.0 * true_positive / (true_positive + false_positive) ;
		    double recall = 1.0 * true_positive / (true_positive + false_negative) ;
		    double fscore =  2.0 * precision * recall / (precision + recall) ;
		    double sensitivity = 1.0 * true_positive / (true_positive + false_negative) ;
		    double specificity = 1.0 * true_negative / (true_negative + false_positive) ;
		    double bac = (sensitivity + specificity) / 2 ;
		    System.out.println("BAC: "+bac+"; Precision: "+precision+"; Recall: "+recall+"; F-Score: "+fscore+"; Sensitivity: "+sensitivity+"; Specificity: "+specificity) ;
		    System.out.print("Cross Validation Accuracy = "+100.0*total_correct/prob.l+"%\n");
		}
	}

    public void run(svm_parameter param, String inputFileName,
            String modelFileName, int numFolds, svm_print_interface print) throws IOException {
        this.param = param;
        input_file_name = inputFileName;
        model_file_name = modelFileName;
        cross_validation = numFolds;
        svm.svm_set_print_string_function(print);
        semantic = true;

        // <code_copy>
        read_problem();
        error_msg = svm.svm_check_parameter(prob,param);

        if(error_msg != null)
        {
            System.err.print("ERROR: "+error_msg+"\n");
            System.exit(1);
        }

        if(cross_validation != 0)
        {
            do_cross_validation();
        }
        else
        {
            model = svm.svm_train(prob,param);
            svm.svm_save_model(model_file_name,model);
        }
        // </code_copy>
    }

    public void run(svm_parameter param, String inputFileName,
            String modelFileName, svm_print_interface print) throws IOException {
        int numFolds = 0;  // default value for cross validation
        run(param, inputFileName, modelFileName, numFolds, print);
    }

    public void run(svm_parameter param, String inputFileName,
            String modelFileName, int numFolds) throws IOException {
        svm_print_interface print = null;  // default printing to stdout
        run(param, inputFileName, modelFileName, numFolds, print);
    }

    public void run(svm_parameter param, String inputFileName,
            String modelFileName) throws IOException {
        int numFolds = 0;  // default value for cross validation
        svm_print_interface print = null;  // default printing to stdout
        run(param, inputFileName, modelFileName, numFolds, print);
    }

	private void run(String argv[]) throws IOException
	{
		parse_command_line(argv);
		read_problem();
		error_msg = svm.svm_check_parameter(prob,param);

		if(error_msg != null)
		{
			System.err.print("ERROR: "+error_msg+"\n");
			System.exit(1);
		}

		if(cross_validation != 0)
		{
			do_cross_validation();
		}
		else
		{
			model = svm.svm_train(prob,param);
			svm.svm_save_model(model_file_name,model);
		}
	}

	public static void main(String argv[]) throws IOException
	{
		svm_train t = new svm_train();
		t.run(argv);
	}

	private static double atof(String s)
	{
		double d = Double.valueOf(s).doubleValue();
		if (Double.isNaN(d) || Double.isInfinite(d))
		{
			System.err.print("NaN or Infinity in input\n");
			System.exit(1);
		}
		return(d);
	}

	private static int atoi(String s)
	{
		return Integer.parseInt(s);
	}

	private void parse_command_line(String argv[])
	{
		int i;
		svm_print_interface print_func = null;	// default printing to stdout

		param = new svm_parameter();
		// default values
		param.svm_type = svm_parameter.C_SVC;
		param.kernel_type = svm_parameter.RBF;
		param.degree = 3;
		param.gamma = 0;	// 1/num_features
		param.coef0 = 0;
		param.nu = 0.5;
		param.cache_size = 100;
		param.C = 1;
		param.eps = 1e-3;
		param.p = 0.1;
		param.shrinking = 1;
		param.probability = 0;
		param.nr_weight = 0;
		param.weight_label = new int[0];
		param.weight = new double[0];
		cross_validation = 0;

		// parse options
		for(i=0;i<argv.length;i++)
		{
			if(argv[i].charAt(0) != '-') break;
			if(++i>=argv.length)
				exit_with_help();
			switch(argv[i-1].charAt(1))
			{
				case 's':
					param.svm_type = atoi(argv[i]);
					break;
				case 't':
					param.kernel_type = atoi(argv[i]);
					break;
				case 'd':
					param.degree = atoi(argv[i]);
					break;
				case 'g':
					param.gamma = atof(argv[i]);
					break;
				case 'r':
					param.coef0 = atof(argv[i]);
					break;
				case 'n':
					param.nu = atof(argv[i]);
					break;
				case 'm':
					param.cache_size = atof(argv[i]);
					break;
				case 'c':
					param.C = atof(argv[i]);
					break;
				case 'f':
					param.ontology_file = argv[i];
					this.semantic = true;
					break;
				case 'e':
					param.eps = atof(argv[i]);
					break;
				case 'p':
					param.p = atof(argv[i]);
					break;
				case 'h':
					param.shrinking = atoi(argv[i]);
					break;
				case 'b':
					param.probability = atoi(argv[i]);
					break;
				case 'q':
					print_func = svm_print_null;
					i--;
					break;
				case 'v':
					cross_validation = 1;
					nr_fold = atoi(argv[i]);
					if(nr_fold < 2)
					{
						System.err.print("n-fold cross validation: n must >= 2\n");
						exit_with_help();
					}
					break;
				case 'w':
					++param.nr_weight;
					{
						int[] old = param.weight_label;
						param.weight_label = new int[param.nr_weight];
						System.arraycopy(old,0,param.weight_label,0,param.nr_weight-1);
					}

					{
						double[] old = param.weight;
						param.weight = new double[param.nr_weight];
						System.arraycopy(old,0,param.weight,0,param.nr_weight-1);
					}

					param.weight_label[param.nr_weight-1] = atoi(argv[i-1].substring(2));
					param.weight[param.nr_weight-1] = atof(argv[i]);
					break;
				default:
					System.err.print("Unknown option: " + argv[i-1] + "\n");
					exit_with_help();
			}
		}

		svm.svm_set_print_string_function(print_func);

		// determine filenames

		if(i>=argv.length)
			exit_with_help();

		input_file_name = argv[i];

		if(i<argv.length-1)
			model_file_name = argv[i+1];
		else
		{
			int p = argv[i].lastIndexOf('/');
			++p;	// whew...
			model_file_name = argv[i].substring(p)+".model";
		}
	}

	// read in a problem (in svmlight format)

	private void read_problem() throws IOException
	{
		BufferedReader fp = new BufferedReader(new FileReader(input_file_name));
		Vector<Double> vy = new Vector<Double>();
		Vector<svm_node[]> vx = new Vector<svm_node[]>();
		int max_index = 0;
		Map<String, Integer> class2index = null ;
		if (semantic) {
		    try {
			class2index = new HashMap<String, Integer>();
			OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
			OWLDataFactory fac = manager.getOWLDataFactory();
			OWLDataFactory factory = fac;
			OWLOntology ont = manager.loadOntologyFromOntologyDocument(new File(param.ontology_file));
			int counter = 0 ;
			for (OWLClass cl : ont.getClassesInSignature()) {
			    String uri = cl.toString().replaceAll("<","").replaceAll(">","");
			    class2index.put(uri, counter) ;
			    counter += 1 ;
			}
			param.class2id = class2index ;
		    } catch (Exception E) {}
		}

		while(true)
		{
			String line = fp.readLine();
			if(line == null) break;

			if (! semantic) {
			    StringTokenizer st = new StringTokenizer(line," \t\n\r\f:");

			    vy.addElement(atof(st.nextToken()));
			    int m = st.countTokens()/2;
			    svm_node[] x = new svm_node[m];
			    for(int j=0;j<m;j++)
				{
				    x[j] = new svm_node();
				    x[j].index = atoi(st.nextToken());
				    x[j].value = atof(st.nextToken());
				}
			    if(m>0) max_index = Math.max(max_index, x[m-1].index);
			    vx.addElement(x);
			} else {
			    String[] toks = line.split("\t") ;
			    vy.addElement(atof(toks[0]));
			    svm_node[] x = new svm_node[toks.length - 1];
			    for (int i = 1 ; i < toks.length ; i++) {
				int j = i-1 ;
				x[j] = new svm_node();
				x[j].index = class2index.get(toks[i]) ;
				x[j].value = 1 ;
				x[j].classURI = toks[i] ;
			    }
			    vx.addElement(x);
			}
		}

		prob = new svm_problem();
		if (semantic) {
		    prob.class2id = class2index;
		}
		prob.l = vy.size();
		prob.x = new svm_node[prob.l][];
		for(int i=0;i<prob.l;i++)
			prob.x[i] = vx.elementAt(i);
		prob.y = new double[prob.l];
		for(int i=0;i<prob.l;i++)
			prob.y[i] = vy.elementAt(i);

		if(param.gamma == 0 && max_index > 0)
			param.gamma = 1.0/max_index;

		if(param.kernel_type == svm_parameter.PRECOMPUTED)
			for(int i=0;i<prob.l;i++)
			{
				if (prob.x[i][0].index != 0)
				{
					System.err.print("Wrong kernel matrix: first column must be 0:sample_serial_number\n");
					System.exit(1);
				}
				if ((int)prob.x[i][0].value <= 0 || (int)prob.x[i][0].value > max_index)
				{
					System.err.print("Wrong input format: sample_serial_number out of range\n");
					System.exit(1);
				}
			}

		fp.close();
	}
}
