package edu.uj.faceRecognizer.faceRecognition;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;
import com.googlecode.javacpp.Loader;
import com.googlecode.javacv.cpp.opencv_contrib;
import com.googlecode.javacv.cpp.opencv_core;
import com.googlecode.javacv.cpp.opencv_objdetect;
import edu.uj.faceRecognizer.faceRecognition.email.MailSender;
import edu.uj.faceRecognizer.faceRecognition.utilities.ToastHelper;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.googlecode.javacv.cpp.opencv_core.*;
import static com.googlecode.javacv.cpp.opencv_highgui.cvLoadImage;
import static com.googlecode.javacv.cpp.opencv_highgui.cvSaveImage;
import static com.googlecode.javacv.cpp.opencv_imgproc.CV_BGR2GRAY;
import static com.googlecode.javacv.cpp.opencv_imgproc.cvCvtColor;
import static com.googlecode.javacv.cpp.opencv_imgproc.cvResize;
import static com.googlecode.javacv.cpp.opencv_objdetect.CV_HAAR_DO_CANNY_PRUNING;
import static com.googlecode.javacv.cpp.opencv_objdetect.cvHaarDetectObjects;

/**
 * User: piotrplaneta
 * Date: 21.01.2013
 * Time: 20:28
 */
public class FaceRecognizer {

    opencv_contrib.FaceRecognizer faceRecognizer;
    public static final int SUBSAMPLING_FACTOR = 4;
    private Map<String, Integer> names;
    private opencv_core.IplImage grayImage;
    private opencv_objdetect.CvHaarClassifierCascade classifier;
    private opencv_core.CvMemStorage storage;
    private opencv_core.CvSeq faces;
    private Context context;

    public float getScaleX(int viewWidth) {
        return viewWidth / (float) grayImage.width();
    }

    public float getScaleY(int viewHeight) {
        return viewHeight / (float) grayImage.height();
    }

    public CvSeq getFaces() {
        return faces;
    }

    public FaceRecognizer(Context context) throws IOException {
        this.context = context;

        // Load the classifier file from Java resources.
        File classifierFile = Loader.extractResource(getClass(),
                "/edu/uj/faceRecognizer/faceRecognition/utilities/haarcascade_frontalface_alt.xml",
                context.getCacheDir(), "classifier", ".xml");
        if (classifierFile == null || classifierFile.length() <= 0) {
            throw new IOException("Could not extract the classifier file from Java resource.");
        }

        // Preload the opencv_objdetect module to work around a known bug.
        Loader.load(opencv_objdetect.class);
        classifier = new opencv_objdetect.CvHaarClassifierCascade(cvLoad(classifierFile.getAbsolutePath()));
        classifierFile.delete();
        if (classifier.isNull()) {
            throw new IOException("Could not load the classifier file.");
        }
        storage = opencv_core.CvMemStorage.create();

        loadFaceRecognizer();
    }

    private void loadFaceRecognizer() {

        File root = new File(Environment.getExternalStorageDirectory().getPath() + "/faceRecognizer/");

        if (root == null) {
            ToastHelper.notify(context, "No training directory");
            return;
        }

        FilenameFilter jpg = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".jpg");
            }
        };
        File[] imageFiles = root.listFiles(jpg);

        opencv_core.MatVector images = new opencv_core.MatVector(imageFiles.length);
        int[] labels = new int[imageFiles.length];

        int counter = 0;
        String label;

        opencv_core.IplImage img;
        opencv_core.IplImage grayImg;

        names = new HashMap<String, Integer>();
        names.put("unknown", -1);

        int i = 0;
        for (File image : imageFiles) {
            img = cvLoadImage(image.getAbsolutePath());
            grayImg = opencv_core.IplImage.create(img.width(), img.height(), IPL_DEPTH_8U, 1);
            cvCvtColor(img, grayImg, CV_BGR2GRAY);

            label = image.getName().split("\\-")[0];

            if(!names.containsKey(label)) {
                i++;
                names.put(label, i);
            }

            images.put(counter, grayImg);
            labels[counter] = i;
            counter++;

            Log.i("faceRecognizer", String.valueOf(label));
        }

        faceRecognizer = com.googlecode.javacv.cpp.opencv_contrib.createFisherFaceRecognizer();

        faceRecognizer.set("threshold", 2000.0);

        try {
            faceRecognizer.train(images, labels);
        } catch (Exception e) {
            ToastHelper.notify(context, "Problem with training");
            Log.e("faceRecognizer", e.getMessage());
        }

    }

    protected void processImage(byte[] data, int width, int height) {
        Log.i("faceRecognizer", "procesimage");
        int f = SUBSAMPLING_FACTOR;
        if (grayImage == null || grayImage.width() != width/f || grayImage.height() != height/f) {
            grayImage = opencv_core.IplImage.create(width / f, height / f, IPL_DEPTH_8U, 1);
        }
        int imageWidth  = grayImage.width();
        int imageHeight = grayImage.height();
        int dataStride = f*width;
        int imageStride = grayImage.widthStep();
        ByteBuffer imageBuffer = grayImage.getByteBuffer();
        for (int y = 0; y < imageHeight; y++) {
            int dataLine = y*dataStride;
            int imageLine = y*imageStride;
            for (int x = 0; x < imageWidth; x++) {
                imageBuffer.put(imageLine + x, data[dataLine + f*x]);
            }
        }

        faces = cvHaarDetectObjects(grayImage, classifier, storage, 1.1, 3, CV_HAAR_DO_CANNY_PRUNING);
        if (faces != null) {
            int total = faces.total();
            if (total > 1) {
                total = 1;
            }
            for (int i = 0; i < total; i++) { //Only one face
                opencv_core.CvRect r = new opencv_core.CvRect(cvGetSeqElem(faces, i));


                opencv_core.IplImage faceToRecognize = getFace(grayImage, r);

                if (faceToRecognize != null) {
                    predictFace(faceToRecognize);
                }
            }
        }

        cvClearMemStorage(storage);
    }

    private void predictFace(opencv_core.IplImage testImage) {
        if (testImage == null) {
            ToastHelper.notify(context, "No test image");
            return;
        }

        if (faceRecognizer != null) {
            try {
                String recognizedPerson = "";
                int predictedLabel = faceRecognizer.predict(testImage);

                for (Map.Entry<String, Integer> entry : names.entrySet()) {
                    if (entry.getValue().equals(predictedLabel)) {

                        ToastHelper.notify(context, entry.getKey());
                        recognizedPerson = entry.getKey();
                    }

                }
                if(predictedLabel != -1) {
                    ((MainActivity) context).stopPreview();
                    MailSender.sendEmail(context, recognizedPerson);
                }

            } catch (Exception e) {
                ToastHelper.notify(context, "Problem with predicting!" + e.getMessage());
                Log.e("faceRecognizer", e.getMessage());
            }
        } else {
            ToastHelper.notify(context, "FaceRecognizer not initialized yet!");
        }



    }

    private IplImage getFace(IplImage image, CvRect region) {
        IplImage imageCropped;
        CvSize size = new CvSize();
        ImageUtilities.getResizedFragment(region);
        Log.i("faceRecognizer", region.toString());

        if(image != null && ImageUtilities.isRegionValid(region, image.width(), image.height())) {
            cvResetImageROI(image);
            cvSetImageROI(image, region);


            size.width(region.width());
            size.height(region.height());

            imageCropped = cvCreateImage(size, IPL_DEPTH_8U, 1);

            cvCopy(image, imageCropped);
            cvResetImageROI(image);
            IplImage imageResized;
            size.width(200);
            size.height(150);
            imageResized = cvCreateImage(size, IPL_DEPTH_8U, 1);
            cvResize(imageCropped, imageResized);

            File testFile = new File(Environment.getExternalStorageDirectory().getPath() + "/faceRecognizerTest/test.png");
            cvSaveImage(testFile.getAbsolutePath(), imageResized);
            return imageResized;
        }

        return null;

    }

}
